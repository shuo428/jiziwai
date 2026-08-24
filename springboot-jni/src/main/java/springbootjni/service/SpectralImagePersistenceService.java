package springbootjni.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import springbootjni.dto.jni.FpgaPayloadPixelDataResponse;
import springbootjni.dto.jni.ImageFrameResponse;
import springbootjni.dto.jni.ImagePixelDataResponse;
import springbootjni.dto.jni.MultiFrameAnalysisResponse;
import springbootjni.service.SpectralImageQualityDispositionService.QualityDispositionResult;
import springbootjni.service.SpectralImageQualityAnalysisService.QualityAnalysisResult;
import springbootjni.service.SpectralImageProcessingService.ProcessingResult;

import javax.imageio.ImageIO;
import javax.annotation.PostConstruct;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 光谱采集与图像文件的持久化服务。
 *
 * <p>设计原则：</p>
 * <ol>
 *     <li>PostgreSQL 保存采集、图像元数据和完整性结论；</li>
 *     <li>约 960 KB/帧的原始 RAW16 数据写入文件系统，避免数据库快速膨胀；</li>
 *     <li>原始文件使用小端序，每个像素占 2 字节、低 12 位有效；</li>
 *     <li>数据库失败时清理刚写入的文件，避免产生没有数据库记录的孤儿文件。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class SpectralImagePersistenceService {
    private static final String PIXEL_FORMAT = "RAW16_LOW12";
    private static final String READOUT_ORDER = "GLUX1605_HDR_4LANE_INTERLEAVED_EFFECTIVE";
    private static final int MAX_HISTORY_SIZE = 50;
    private static final int DEFAULT_PIXEL_WINDOW_SIZE = 16;
    private static final int MAX_PIXEL_WINDOW_SIZE = 64;
    private static final int DEFAULT_FPGA_PAYLOAD_WINDOW_SIZE = 256;
    private static final int MAX_FPGA_PAYLOAD_WINDOW_SIZE = 4096;
    private static final int HDR_HG_BLEND_START = 3400;
    private static final int HDR_HG_BLEND_END = 3900;
    private static final int HDR_RAW12_MAX = 4095;
    private static final int HDR_MIN_GAIN_SAMPLE_HG = 128;
    private static final int HDR_MAX_GAIN_SAMPLE_HG = 3200;
    private static final int HDR_MIN_GAIN_SAMPLE_LG = 32;
    private static final int HDR_MAX_GAIN_SAMPLE_LG = 3800;
    private static final double HDR_DEFAULT_GAIN_RATIO = 4.0d;
    private static final double HDR_MIN_GAIN_RATIO = 1.0d;
    private static final double HDR_MAX_GAIN_RATIO = 64.0d;
    private static final int HDR_MIN_RELIABLE_GAIN_SAMPLE_COUNT = 32;
    private static final double HDR_WARNING_BOTH_SATURATED_RATIO = 0.001d;
    private static final double HDR_FAIL_BOTH_SATURATED_RATIO = 0.01d;
    private static final double HDR_WARNING_LG_SATURATED_RATIO = 0.005d;
    private static final double HDR_WARNING_HG_USED_RATIO_LOW = 0.20d;
    private static final String[] HEX_BYTE_LOOKUP = buildHexByteLookup();

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SpectralImageQualityAnalysisService qualityAnalysisService;
    private final SpectralImageQualityDispositionService qualityDispositionService;
    private final SpectralImageProcessingService imageProcessingService;
    private final SpectralMultiFrameQualityAnalysisService multiFrameQualityAnalysisService;
    private final SpectralCalibrationService calibrationService;

    @Value("${spectral.storage.root:D:/GraduationProject/spectral-images}")
    private String storageRoot;

    /**
     * 为已有数据库补齐质量处置字段。
     *
     * <p>项目早期版本已经创建过 t_image_quality_analysis 表；单纯修改 CREATE TABLE
     * 无法让 PostgreSQL 自动给旧表增加新列。这里使用 IF EXISTS / IF NOT EXISTS 做
     * 幂等升级，保证老库启动后也能保存处置策略结果。</p>
     */
    @PostConstruct
    public void ensureQualityDispositionColumns() {
        jdbcTemplate.execute(
                "ALTER TABLE IF EXISTS t_spectral_capture " +
                        "DROP CONSTRAINT IF EXISTS t_spectral_capture_capture_scene_check");
        jdbcTemplate.execute(
                "ALTER TABLE IF EXISTS t_spectral_capture " +
                        "ADD CONSTRAINT t_spectral_capture_capture_scene_check " +
                        "CHECK (capture_scene IN ('NORMAL','DARK','FLAT','HDR','HDR_DARK','HDR_FLAT'," +
                        "'HALF_SATURATION','SATURATION','NARROW_LINE','POINT_SOURCE'))");
        jdbcTemplate.execute(
                "ALTER TABLE IF EXISTS t_spectral_image " +
                        "ADD COLUMN IF NOT EXISTS calibrated_raw_storage_uri TEXT");
        jdbcTemplate.execute(
                "ALTER TABLE IF EXISTS t_spectral_image " +
                        "ADD COLUMN IF NOT EXISTS calibrated_preview_storage_uri TEXT");
        jdbcTemplate.execute(
                "ALTER TABLE IF EXISTS t_spectral_image " +
                        "ADD COLUMN IF NOT EXISTS fpga_payload_storage_uri TEXT");
        jdbcTemplate.execute(
                "ALTER TABLE IF EXISTS t_spectral_image " +
                        "ADD COLUMN IF NOT EXISTS fpga_payload_sha256 VARCHAR(64)");
        jdbcTemplate.execute(
                "ALTER TABLE IF EXISTS t_spectral_image " +
                        "ADD COLUMN IF NOT EXISTS readout_order VARCHAR(64)");
        jdbcTemplate.execute(
                "ALTER TABLE IF EXISTS t_spectral_image " +
                        "ADD COLUMN IF NOT EXISTS hg_raw_storage_uri TEXT");
        jdbcTemplate.execute(
                "ALTER TABLE IF EXISTS t_spectral_image " +
                        "ADD COLUMN IF NOT EXISTS lg_raw_storage_uri TEXT");
        jdbcTemplate.execute(
                "ALTER TABLE IF EXISTS t_spectral_image " +
                        "ADD COLUMN IF NOT EXISTS hg_preview_storage_uri TEXT");
        jdbcTemplate.execute(
                "ALTER TABLE IF EXISTS t_spectral_image " +
                        "ADD COLUMN IF NOT EXISTS lg_preview_storage_uri TEXT");
        jdbcTemplate.execute(
                "ALTER TABLE IF EXISTS t_spectral_image " +
                        "ADD COLUMN IF NOT EXISTS hdr_fusion_mask_storage_uri TEXT");
        jdbcTemplate.execute(
                "ALTER TABLE IF EXISTS t_spectral_image " +
                        "ADD COLUMN IF NOT EXISTS hdr_gain_ratio DOUBLE PRECISION");
        jdbcTemplate.execute(
                "ALTER TABLE IF EXISTS t_spectral_image " +
                        "ADD COLUMN IF NOT EXISTS hdr_fusion_details JSONB NOT NULL DEFAULT '{}'::jsonb");
        jdbcTemplate.execute(
                "ALTER TABLE IF EXISTS t_image_quality_analysis " +
                        "ADD COLUMN IF NOT EXISTS disposition_status VARCHAR(32) NOT NULL DEFAULT 'MANUAL_REVIEW'");
        jdbcTemplate.execute(
                "ALTER TABLE IF EXISTS t_image_quality_analysis " +
                        "ADD COLUMN IF NOT EXISTS usable_for_spectral BOOLEAN NOT NULL DEFAULT FALSE");
        jdbcTemplate.execute(
                "ALTER TABLE IF EXISTS t_image_quality_analysis " +
                        "ADD COLUMN IF NOT EXISTS disposition_message TEXT");
        jdbcTemplate.execute(
                "ALTER TABLE IF EXISTS t_image_quality_analysis " +
                        "ADD COLUMN IF NOT EXISTS recommended_actions JSONB NOT NULL " +
                        "DEFAULT '{\"actions\":[],\"reasonCodes\":[]}'::JSONB");
    }

    /**
     * 在真正向 FPGA 发送触发命令之前创建采集记录。
     * 即使后续超时或FPGA报错，这次尝试也不会从数据库中“消失”。
     */
    public long createCapture(Long userId,
                              String requestId,
                              String captureScene,
                              Map<String, Object> configSnapshot) {
        String normalizedCaptureScene = normalizeCaptureScene(captureScene);
        String configJson = toJson(configSnapshot);
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO t_spectral_capture " +
                        "(request_id, user_id, capture_scene, capture_status, trigger_time, config_snapshot) " +
                        "VALUES (?, ?, ?, 'WAITING', CURRENT_TIMESTAMP, CAST(? AS jsonb)) RETURNING id",
                Long.class,
                requestId,
                userId,
                normalizedCaptureScene,
                configJson);
        if (id == null) {
            throw new IllegalStateException("创建采集记录后未返回主键");
        }
        return id;
    }

    /**
     * 保存一张已经通过 native 完整性校验的图像。
     *
     * <p>native 只有在魔数、版本、尺寸、像素格式、Payload长度、可选CRC和RAW12高位
     * 均通过后才调用Java，因此这里写入的完整性记录可以明确标记为通过。</p>
     */
    @Transactional
    public ImageFrameResponse saveSuccessfulFrame(long captureId,
                                                  Long userId,
                                                  String requestId,
                                                  int width,
                                                  int height,
                                                  short[] pixels16,
                                                  byte[] pixels8,
                                                  byte[] fpgaPayload,
                                                  String pixelFormat,
                                                  String readoutOrder,
                                                  boolean verifyCrc,
                                                  boolean autoProcess,
                                                  String captureScene,
                                                  long elapsedMs) {
        return saveSuccessfulFrame(
                captureId,
                userId,
                requestId,
                width,
                height,
                pixels16,
                pixels8,
                fpgaPayload,
                pixelFormat,
                readoutOrder,
                verifyCrc,
                autoProcess,
                captureScene,
                elapsedMs,
                null);
    }

    private ImageFrameResponse saveSuccessfulFrame(long captureId,
                                                   Long userId,
                                                   String requestId,
                                                   int width,
                                                   int height,
                                                   short[] pixels16,
                                                   byte[] pixels8,
                                                   byte[] fpgaPayload,
                                                   String pixelFormat,
                                                   String readoutOrder,
                                                   boolean verifyCrc,
                                                   boolean autoProcess,
                                                   String captureScene,
                                                   long elapsedMs,
                                                   PreprocessingResult lockedPreprocessing) {
        String normalizedCaptureScene = normalizeCaptureScene(captureScene);
        boolean calibrationCapture = isCalibrationCapture(normalizedCaptureScene);
        String normalizedPixelFormat = (pixelFormat == null || pixelFormat.trim().isEmpty())
                ? PIXEL_FORMAT
                : pixelFormat.trim().toUpperCase();
        String normalizedReadoutOrder = (readoutOrder == null || readoutOrder.trim().isEmpty())
                ? READOUT_ORDER
                : readoutOrder.trim().toUpperCase();
        validateFrameBuffers(width, height, pixels16, pixels8, fpgaPayload);

        Path frameDirectory = buildFrameDirectory(requestId);
        try {
            Files.createDirectories(frameDirectory);

            byte[] fpgaPayloadBytes = Arrays.copyOf(fpgaPayload, fpgaPayload.length);
            byte[] rawBytes = toLittleEndianRawBytes(pixels16);
            Path fpgaPayloadFile = frameDirectory.resolve("fpga_payload.bin");
            Path rawFile = frameDirectory.resolve("raw16le.bin");
            Path previewFile = frameDirectory.resolve("preview.png");
            Files.write(fpgaPayloadFile, fpgaPayloadBytes);
            Files.write(rawFile, rawBytes);
            writePreviewPng(width, height, pixels8, previewFile);

            String fpgaPayloadRelativeUri = toStorageUri(fpgaPayloadFile);
            String rawRelativeUri = toStorageUri(rawFile);
            String previewRelativeUri = toStorageUri(previewFile);
            String fpgaPayloadSha256 = sha256Hex(fpgaPayloadBytes);
            String rawSha256 = sha256Hex(rawBytes);

            Long imageId = jdbcTemplate.queryForObject(
                    "INSERT INTO t_spectral_image " +
                            "(capture_id, width, height, pixel_format, payload_length, raw_storage_uri, " +
                            "preview_storage_uri, raw_sha256, fpga_payload_storage_uri, fpga_payload_sha256, " +
                            "readout_order, received_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP) RETURNING id",
                    Long.class,
                    captureId,
                    width,
                    height,
                    normalizedPixelFormat,
                    fpgaPayloadBytes.length,
                    rawRelativeUri,
                    previewRelativeUri,
                    rawSha256,
                    fpgaPayloadRelativeUri,
                    fpgaPayloadSha256,
                    normalizedReadoutOrder);
            if (imageId == null) {
                throw new IllegalStateException("保存图像元数据后未返回主键");
            }

            Map<String, Object> details = new LinkedHashMap<>();
            details.put("verifyCrc", verifyCrc);
            details.put("autoProcess", calibrationCapture ? false : autoProcess);
            details.put("captureScene", normalizedCaptureScene);
            details.put("fpgaPayloadStorageUri", fpgaPayloadRelativeUri);
            details.put("fpgaPayloadSha256", fpgaPayloadSha256);
            details.put("pixelFormat", normalizedPixelFormat);
            details.put("readoutOrder", normalizedReadoutOrder);
            details.put("fpgaPayloadSemantic", isRowMajorReadout(normalizedReadoutOrder)
                    ? "original FPGA effective pixel payload in ROW_MAJOR order"
                    : "original FPGA effective pixel payload before GLUX1605BSI lane reorder");
            details.put("rawStorageSemantic", isRowMajorReadout(normalizedReadoutOrder)
                    ? "row-major RAW16 image in normal image order"
                    : "row-major RAW16 image after GLUX1605BSI HDR effective-pixel lane reorder");
            if (calibrationCapture) {
                details.put("calibrationCapturePolicy", "calibration samples keep raw pixels only; active calibration package and image processing are skipped");
            }
            details.put("rawHighBitsChecked", true);
            details.put("protocolChecks", "magic,version,headerLength,dimensions,pixelFormat,payloadLength");

            jdbcTemplate.update(
                    "INSERT INTO t_image_integrity_analysis " +
                            "(capture_id, image_id, passed, result_code, result_message, crc_ok, size_ok, " +
                            "format_ok, expected_bytes, received_bytes, elapsed_ms, details) " +
                            "VALUES (?, ?, TRUE, 'OK', ?, ?, TRUE, TRUE, ?, ?, ?, CAST(? AS jsonb))",
                    captureId,
                    imageId,
                    "图像接收完整",
                    verifyCrc ? Boolean.TRUE : null,
                    fpgaPayloadBytes.length,
                    fpgaPayloadBytes.length,
                    elapsedMs,
                    toJson(details));

            /*
             * 接收完整性只说明“这张图没有在传输和组帧过程中损坏”；
             * 基础质量分析继续判断“这张完整图是否适合进入后续图像处理/光谱提取”。
             *
             * 这里使用原始 RAW12 像素 pixels16，而不是 preview.png：
             * 1. preview.png 是 8-bit 显示图，已经丢失 12-bit 定量动态范围；
             * 2. 坏点、饱和比例、黑像素比例等指标必须基于原始数字量 DN；
             * 3. 后续暗场/平场校正也应基于 RAW 或处理后的 RAW，而不是前端预览图。
             */
            // 原始 RAW 的硬性检查始终执行：它回答的是曝光/ADC 裁剪是否已经不可逆，
            // 不会因后续暗场扣除或平场增益校正而被掩盖。
            QualityAnalysisResult rawQuality = qualityAnalysisService.analyze(width, height, pixels16);
            Map<String, Object> rawHardQuality = buildRawHardQualitySnapshot(rawQuality);

            Map<String, Object> calibrationDetails;
            short[] qualityPixels16;
            SavedCalibrationVersion calibrationVersion = null;
            QualityAnalysisResult quality;
            Map<String, Object> calibratedQuality;
            if (calibrationCapture) {
                // 暗场/平场是用于建立新参考的“原始样本”，不能再套当前已启用的校准包，
                // 也不能做坏点/行列等图像处理，否则会污染新校准参考。
                calibrationDetails = buildCalibrationCaptureSkipDetails(normalizedCaptureScene);
                qualityPixels16 = pixels16;
                quality = rawQuality;
                calibratedQuality = null;
            } else {
                /*
                 * 普通单帧在这里读取普通全局校准包。
                 * HDR采集会在进入这里之前已经分别对HG/LG应用HDR专用校准包，再生成校准后融合主图；
                 * 因此它会传入 lockedPreprocessing，避免“先融合、再套普通暗场/平场”的错误链路。
                 */
                PreprocessingResult preprocessing = lockedPreprocessing;
                if (preprocessing == null) {
                    // 新帧锁定当前校准包：先暗场/平场，再按该包的多帧缺陷地图校正稳定缺陷。
                    // 之后所有处理与光谱提取都会从质量详情中的会话快照恢复同一版本，而不是读取“最新”参考。
                    SpectralCalibrationService.CalibrationProfile calibrationProfile =
                            calibrationService.loadActiveProfile(userId, width, height);
                    preprocessing = applyCalibrationPackage(calibrationProfile, pixels16);
                }
                calibrationDetails = new LinkedHashMap<>(preprocessing.calibrationDetails);
                boolean calibrationApplied = Boolean.TRUE.equals(calibrationDetails.get("calibrationApplied"));
                boolean defectMapApplied = Boolean.TRUE.equals(calibrationDetails.get("defectMapApplied"));
                boolean preprocessingApplied = calibrationApplied || defectMapApplied;
                qualityPixels16 = preprocessing.pixels16;
                calibrationVersion = saveCalibratedVersionIfNeeded(
                        imageId,
                        frameDirectory,
                        width,
                        height,
                        qualityPixels16,
                        calibrationDetails);
                quality = preprocessingApplied
                        ? qualityAnalysisService.analyze(width, height, qualityPixels16)
                        : rawQuality;
                calibratedQuality = preprocessingApplied ? qualityToMap(quality) : null;
            }
            attachCalibrationQualityAudit(quality, calibrationDetails, rawHardQuality, calibratedQuality);
            // 原始 RAW 的饱和、全黑/欠曝和近常数图属于不可逆风险。即使校准后的数值看似改善，
            // 也不能让这类帧绕过最终处置，因此最终状态取“原始硬性检查”和“校准后完整检查”的较严重者。
            quality = applyRawHardQualityGate(quality, rawHardQuality);
            QualityDispositionResult disposition = qualityDispositionService.decide(quality);
            saveQualityAnalysis(imageId, quality, disposition);
            SavedProcessingResult processing = null;
            if (!calibrationCapture && autoProcess) {
                processing = tryProcessAndSaveImage(
                        captureId,
                        imageId,
                        width,
                        height,
                        frameDirectory,
                        qualityPixels16,
                        quality,
                        disposition,
                        calibrationDetails);
                if (processing == null) {
                    saveSkippedProcessingLog(captureId, imageId, disposition);
                    processing = new SavedProcessingResult(
                            "SKIPPED",
                            "自动处理已跳过：当前图片没有可自动修复动作，或无需修复。",
                            skippedProcessingDetails(disposition));
                }
            }

            jdbcTemplate.update(
                    "UPDATE t_spectral_capture SET capture_status='RECEIVED', completed_at=CURRENT_TIMESTAMP, " +
                            "error_message=NULL WHERE id=?",
                    captureId);

            return buildResponse(
                    imageId,
                    captureId,
                    requestId,
                    normalizedCaptureScene,
                    OffsetDateTime.now(ZoneOffset.UTC),
                    width,
                    height,
                    fpgaPayloadBytes.length,
                    previewFile,
                    calibrationVersion == null ? null : calibrationVersion.calibratedPreviewFile,
                    fpgaPayloadRelativeUri,
                    fpgaPayloadSha256,
                    normalizedReadoutOrder,
                    true,
                    "OK",
                    quality,
                    disposition,
                    processing);
        } catch (RuntimeException | IOException ex) {
            deleteDirectoryQuietly(frameDirectory);
            throw new IllegalStateException("保存光谱图像失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 保存一次 HDR 双增益采集结果。
     *
     * <p>FPGA 一次触发返回一个双平面 payload：先完整 HG，再完整 LG。native 已经把两个
     * 平面分别按当前读出顺序重排成正常行列坐标。Java 侧在这里做三件事：</p>
     * <ol>
     *     <li>估计 HG/LG 增益比例，并生成一张用于后续质量分析和光谱提取的融合 RAW；</li>
     *     <li>复用普通图像的完整性入库、基础质量分析、校准/缺陷修复和可选自动处理流程；</li>
     *     <li>额外保存 HG、LG 两个输入平面的 RAW/预览图，以及每个像素采用 HG/LG/混合的掩码。</li>
     * </ol>
     */
    @Transactional
    public ImageFrameResponse saveSuccessfulHdrFrame(long captureId,
                                                     Long userId,
                                                     String requestId,
                                                     int width,
                                                     int height,
                                                     short[] hgPixels16,
                                                     short[] lgPixels16,
                                                     byte[] fpgaPayload,
                                                     String pixelFormat,
                                                     String readoutOrder,
                                                     boolean verifyCrc,
                                                     boolean autoProcess,
                                                     String captureScene,
                                                     long elapsedMs) {
        String normalizedCaptureScene = normalizeCaptureScene(captureScene);
        validateHdrFrameBuffers(width, height, hgPixels16, lgPixels16, fpgaPayload);
        HdrFusionResult fusion;
        PreprocessingResult lockedPreprocessing = null;
        if ("HDR_DARK".equals(normalizedCaptureScene)) {
            fusion = buildHdrDarkDiagnosticComposite(width, height, hgPixels16, lgPixels16);
        } else if ("HDR_FLAT".equals(normalizedCaptureScene)) {
            fusion = buildHdrFlatDiagnosticComposite(width, height, hgPixels16, lgPixels16);
        } else {
            /*
             * HDR正式光谱图像必须先分别校准HG/LG，再融合。
             * raw_storage_uri保存的是未校准HG/LG融合主图；如果HDR校准包启用，
             * calibrated_raw_storage_uri保存“HG校准 + LG校准 + 再融合”的版本，后续质量分析、
             * 图像处理和光谱提取都基于这个校准后融合版本。
             */
            fusion = fuseHdrPlanes(width, height, hgPixels16, lgPixels16);
            SpectralCalibrationService.HdrCalibrationProfile hdrCalibrationProfile =
                    calibrationService.loadActiveHdrProfile(userId, width, height);
            SpectralCalibrationService.HdrCalibrationApplicationResult hdrCalibration =
                    hdrCalibrationProfile.apply(hgPixels16, lgPixels16);
            Map<String, Object> hdrCalibrationDetails = new LinkedHashMap<>(hdrCalibration.getDetails());
            boolean hdrCalibrationApplied = Boolean.TRUE.equals(hdrCalibrationDetails.get("calibrationApplied"));
            boolean hdrDefectMapApplied = Boolean.TRUE.equals(hdrCalibrationDetails.get("defectMapApplied"));
            if (hdrCalibrationApplied || hdrDefectMapApplied) {
                HdrFusionResult calibratedFusion = fuseHdrPlanes(
                        width,
                        height,
                        hdrCalibration.getHgPixels16(),
                        hdrCalibration.getLgPixels16());
                hdrCalibrationDetails.put("hdrFusionAfterCalibration", calibratedFusion.details);
                lockedPreprocessing = new PreprocessingResult(calibratedFusion.fusedPixels16, hdrCalibrationDetails);
            } else {
                lockedPreprocessing = new PreprocessingResult(fusion.fusedPixels16, hdrCalibrationDetails);
            }
        }
        Path frameDirectory = buildFrameDirectory(requestId);
        try {
            ImageFrameResponse response = saveSuccessfulFrame(
                    captureId,
                    userId,
                    requestId,
                    width,
                    height,
                    fusion.fusedPixels16,
                    toPreviewBytes(fusion.fusedPixels16),
                    fpgaPayload,
                    pixelFormat,
                    readoutOrder,
                    verifyCrc,
                    autoProcess,
                    normalizedCaptureScene,
                    elapsedMs,
                    lockedPreprocessing);

            Path hdrDirectory = frameDirectory.resolve("hdr");
            Files.createDirectories(hdrDirectory);
            Path hgRawFile = hdrDirectory.resolve("hg.raw16le.bin");
            Path lgRawFile = hdrDirectory.resolve("lg.raw16le.bin");
            Path hgPreviewFile = hdrDirectory.resolve("hg-preview.png");
            Path lgPreviewFile = hdrDirectory.resolve("lg-preview.png");
            Path fusionMaskFile = hdrDirectory.resolve("fusion-mask.bin");
            Files.write(hgRawFile, toLittleEndianRawBytes(hgPixels16));
            Files.write(lgRawFile, toLittleEndianRawBytes(lgPixels16));
            writePreviewPng(width, height, toPreviewBytes(hgPixels16), hgPreviewFile);
            writePreviewPng(width, height, toPreviewBytes(lgPixels16), lgPreviewFile);
            Files.write(fusionMaskFile, fusion.fusionMask);

            String hgRawUri = toStorageUri(hgRawFile);
            String lgRawUri = toStorageUri(lgRawFile);
            String hgPreviewUri = toStorageUri(hgPreviewFile);
            String lgPreviewUri = toStorageUri(lgPreviewFile);
            String fusionMaskUri = toStorageUri(fusionMaskFile);
            jdbcTemplate.update(
                    "UPDATE t_spectral_image SET hg_raw_storage_uri=?, lg_raw_storage_uri=?, " +
                            "hg_preview_storage_uri=?, lg_preview_storage_uri=?, " +
                            "hdr_fusion_mask_storage_uri=?, hdr_gain_ratio=?, " +
                            "hdr_fusion_details=CAST(? AS jsonb) WHERE id=?",
                    hgRawUri,
                    lgRawUri,
                    hgPreviewUri,
                    lgPreviewUri,
                    fusionMaskUri,
                    fusion.gainRatio,
                    toJson(fusion.details),
                    response.getId());

            response.setHgRawStorageUri(hgRawUri);
            response.setLgRawStorageUri(lgRawUri);
            response.setHgPreviewStorageUri(hgPreviewUri);
            response.setLgPreviewStorageUri(lgPreviewUri);
            response.setHdrFusionMaskStorageUri(fusionMaskUri);
            response.setHgImageDataUrl(encodePreviewDataUrl(hgPreviewFile));
            response.setLgImageDataUrl(encodePreviewDataUrl(lgPreviewFile));
            response.setHdrGainRatio(fusion.gainRatio);
            response.setHdrFusionDetails(fusion.details);
            return response;
        } catch (RuntimeException | IOException ex) {
            deleteDirectoryQuietly(frameDirectory);
            throw new IllegalStateException("保存HDR光谱图像失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 将单张图像基础质量分析结果写入 t_image_quality_analysis。
     *
     * <p>核心字段保存最常用、最需要查询/排序的指标；详细阈值、分位数、异常行列样本、
     * 坏点样本等放入 details JSONB。这样既能支持前端列表快速查询，也避免为了每一个
     * 未来指标反复修改数据库结构。</p>
     */
    private void saveQualityAnalysis(Long imageId,
                                     QualityAnalysisResult quality,
                                     QualityDispositionResult disposition) {
        jdbcTemplate.update(
                "INSERT INTO t_image_quality_analysis " +
                        "(image_id, quality_status, analysis_version, pixel_min, pixel_max, pixel_mean, " +
                        "pixel_stddev, black_pixel_ratio, saturation_pixel_ratio, abnormal_row_count, " +
                        "abnormal_column_count, bad_pixel_count, disposition_status, usable_for_spectral, " +
                        "disposition_message, recommended_actions, details, analyzed_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), " +
                        "CAST(? AS jsonb), CURRENT_TIMESTAMP) " +
                        "ON CONFLICT (image_id) DO UPDATE SET " +
                        "quality_status=EXCLUDED.quality_status, " +
                        "analysis_version=EXCLUDED.analysis_version, " +
                        "pixel_min=EXCLUDED.pixel_min, " +
                        "pixel_max=EXCLUDED.pixel_max, " +
                        "pixel_mean=EXCLUDED.pixel_mean, " +
                        "pixel_stddev=EXCLUDED.pixel_stddev, " +
                        "black_pixel_ratio=EXCLUDED.black_pixel_ratio, " +
                        "saturation_pixel_ratio=EXCLUDED.saturation_pixel_ratio, " +
                        "abnormal_row_count=EXCLUDED.abnormal_row_count, " +
                        "abnormal_column_count=EXCLUDED.abnormal_column_count, " +
                        "bad_pixel_count=EXCLUDED.bad_pixel_count, " +
                        "disposition_status=EXCLUDED.disposition_status, " +
                        "usable_for_spectral=EXCLUDED.usable_for_spectral, " +
                        "disposition_message=EXCLUDED.disposition_message, " +
                        "recommended_actions=EXCLUDED.recommended_actions, " +
                        "details=EXCLUDED.details, " +
                        "analyzed_at=CURRENT_TIMESTAMP",
                imageId,
                quality.getQualityStatus(),
                quality.getAnalysisVersion(),
                quality.getPixelMin(),
                quality.getPixelMax(),
                quality.getPixelMean(),
                quality.getPixelStddev(),
                quality.getBlackPixelRatio(),
                quality.getSaturationPixelRatio(),
                quality.getAbnormalRowCount(),
                quality.getAbnormalColumnCount(),
                quality.getBadPixelCount(),
                disposition.getDispositionStatus(),
                disposition.isUsableForSpectral(),
                disposition.getSummaryMessage(),
                toJson(disposition.getDetails()),
                toJson(quality.getDetails()));
    }

    /**
     * 按质量处置建议执行当前阶段可安全执行的图像处理。
     *
     * <p>处理失败不回滚原图保存：原始 RAW、预览图、基础质量分析和处置建议仍然有效。
     * 失败信息会写入 t_image_action_log，便于后续定位算法或文件系统问题。</p>
     */
    private SavedProcessingResult tryProcessAndSaveImage(long captureId,
                                                         Long imageId,
                                                         int width,
                                                         int height,
                                                         Path frameDirectory,
                                                         short[] pixels16,
                                                         QualityAnalysisResult quality,
                                                         QualityDispositionResult disposition,
                                                         Map<String, Object> calibrationDetails) {
        try {
            ProcessingResult result = imageProcessingService.processIfRecommended(
                    width,
                    height,
                    pixels16,
                    quality,
                    disposition);
            if (result == null) {
                return null;
            }

            Path processedDirectory = frameDirectory.resolve("processed");
            Files.createDirectories(processedDirectory);
            Path processedRawFile = processedDirectory.resolve("raw16le.bin");
            Path processedPreviewFile = processedDirectory.resolve("preview.png");
            Files.write(processedRawFile, toLittleEndianRawBytes(result.getProcessedPixels16()));
            writePreviewPng(width, height, toPreviewBytes(result.getProcessedPixels16()), processedPreviewFile);

            String processedRawUri = toStorageUri(processedRawFile);
            String processedPreviewUri = toStorageUri(processedPreviewFile);
            Map<String, Object> details = buildProcessingDetails(
                    result,
                    processedRawUri,
                    processedPreviewUri,
                    calibrationDetails);

            jdbcTemplate.update(
                    "UPDATE t_spectral_image SET processed_storage_uri=? WHERE id=?",
                    processedPreviewUri,
                    imageId);
            jdbcTemplate.update(
                    "INSERT INTO t_image_action_log " +
                            "(capture_id, image_id, action_type, action_status, reason, output_storage_uri, details) " +
                            "VALUES (?, ?, 'CORRECT', 'SUCCESS', ?, ?, CAST(? AS jsonb))",
                    captureId,
                    imageId,
                    result.getSummaryMessage(),
                    processedPreviewUri,
                    toJson(details));

            return new SavedProcessingResult(result, processedPreviewFile, details);
        } catch (RuntimeException | IOException ex) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("processingVersion", SpectralImageProcessingService.PROCESSING_VERSION);
            details.put("errorType", ex.getClass().getSimpleName());
            details.put("errorMessage", ex.getMessage());
            jdbcTemplate.update(
                    "INSERT INTO t_image_action_log " +
                            "(capture_id, image_id, action_type, action_status, reason, details) " +
                            "VALUES (?, ?, 'CORRECT', 'FAILED', ?, CAST(? AS jsonb))",
                    captureId,
                    imageId,
                    "图像处理失败: " + ex.getMessage(),
                    toJson(details));
            return new SavedProcessingResult(
                    "FAILED",
                    "图像处理失败: " + ex.getMessage(),
                    details);
        }
    }

    /**
     * 统一执行校准包预处理：暗场/平场校正后，按需应用该包已经确认的稳定缺陷地图。
     * 单帧后续修复只接收这里的输出，因此不会再次把固定缺陷作为“本帧新坏点”处理。
     */
    private PreprocessingResult applyCalibrationPackage(
            SpectralCalibrationService.CalibrationProfile calibrationProfile,
            short[] rawPixels16) {
        SpectralCalibrationService.CalibrationApplicationResult calibration = calibrationProfile.apply(rawPixels16);
        short[] pixels16 = calibration.getPixels16();
        Map<String, Object> details = new LinkedHashMap<>(calibration.getDetails());
        SpectralMultiFrameQualityAnalysisService.DefectMap defectMap = calibrationProfile.getDefectMap();
        if (defectMap == null) {
            details.put("defectMapApplied", false);
            return new PreprocessingResult(pixels16, details);
        }
        ProcessingResult mapResult = imageProcessingService.processWithMultiFrameDefectMap(
                calibrationProfile.getWidth(), calibrationProfile.getHeight(), pixels16, defectMap);
        if (mapResult == null) {
            details.put("defectMapApplied", false);
            details.put("defectMapMessage", "已选择缺陷地图，但其中没有可校正的稳定缺陷坐标");
            return new PreprocessingResult(pixels16, details);
        }
        details.put("defectMapApplied", true);
        details.put("defectMapExecutedActions", mapResult.getExecutedActions());
        details.put("defectMapCorrectedBadPixelCount", mapResult.getCorrectedBadPixelCount());
        details.put("defectMapCorrectedRowCount", mapResult.getCorrectedRowCount());
        details.put("defectMapCorrectedColumnCount", mapResult.getCorrectedColumnCount());
        return new PreprocessingResult(mapResult.getProcessedPixels16(), details);
    }

    /**
     * 保存“校准后图像版本”。
     *
     * <p>这里的校准后版本不是额外增强图，而是严格由采集时锁定的校准包生成：
     * 暗场扣除、平场校正，以及可选的稳定缺陷地图修复。保存它的目的有两个：
     * 一是让前端能明确展示 raw / calibrated / processed 三个版本；二是让光谱提取
     * 可以直接读取同一版像素，避免每次提取时重新临时计算导致来源不清楚。</p>
     */
    private SavedCalibrationVersion saveCalibratedVersionIfNeeded(Long imageId,
                                                                  Path frameDirectory,
                                                                  int width,
                                                                  int height,
                                                                  short[] pixels16,
                                                                  Map<String, Object> calibrationDetails)
            throws IOException {
        boolean calibrationApplied = Boolean.TRUE.equals(calibrationDetails.get("calibrationApplied"));
        boolean defectMapApplied = Boolean.TRUE.equals(calibrationDetails.get("defectMapApplied"));
        if (!calibrationApplied && !defectMapApplied) {
            return null;
        }

        Path calibratedDirectory = frameDirectory.resolve("calibrated");
        Files.createDirectories(calibratedDirectory);
        Path calibratedRawFile = calibratedDirectory.resolve("raw16le.bin");
        Path calibratedPreviewFile = calibratedDirectory.resolve("preview.png");
        Files.write(calibratedRawFile, toLittleEndianRawBytes(pixels16));
        writePreviewPng(width, height, toPreviewBytes(pixels16), calibratedPreviewFile);

        String calibratedRawUri = toStorageUri(calibratedRawFile);
        String calibratedPreviewUri = toStorageUri(calibratedPreviewFile);
        jdbcTemplate.update(
                "UPDATE t_spectral_image SET calibrated_raw_storage_uri=?, calibrated_preview_storage_uri=? WHERE id=?",
                calibratedRawUri,
                calibratedPreviewUri,
                imageId);
        calibrationDetails.put("calibratedRawStorageUri", calibratedRawUri);
        calibrationDetails.put("calibratedPreviewStorageUri", calibratedPreviewUri);
        return new SavedCalibrationVersion(calibratedRawFile, calibratedPreviewFile);
    }

    private Map<String, Object> buildProcessingDetails(ProcessingResult result,
                                                       String processedRawUri,
                                                       String processedPreviewUri,
                                                       Map<String, Object> calibrationDetails) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("processingVersion", SpectralImageProcessingService.PROCESSING_VERSION);
        details.put("processingStatus", result.getProcessingStatus());
        details.put("executedActions", result.getExecutedActions());
        details.put("skippedActionCodes", result.getSkippedActionCodes());
        details.put("correctedRowCount", result.getCorrectedRowCount());
        details.put("correctedColumnCount", result.getCorrectedColumnCount());
        details.put("correctedBadPixelCount", result.getCorrectedBadPixelCount());
        details.put("processedRawStorageUri", processedRawUri);
        details.put("processedPreviewStorageUri", processedPreviewUri);
        if (calibrationDetails != null) {
            // 处理后复检同样必须知道其输入是否已经过暗场/平场校准，防止用户把两个版本混淆。
            result.getProcessedQuality().getDetails().put("calibration", new LinkedHashMap<>(calibrationDetails));
            details.put("calibration", new LinkedHashMap<>(calibrationDetails));
            details.put("preprocessingApplied", Boolean.TRUE.equals(calibrationDetails.get("calibrationApplied"))
                    || Boolean.TRUE.equals(calibrationDetails.get("defectMapApplied")));
        }
        details.put("processedQuality", qualityToMap(result.getProcessedQuality()));
        details.put("processedDisposition", dispositionToMap(result.getProcessedDisposition()));
        return details;
    }

    private void saveSkippedProcessingLog(Long captureId,
                                          Long imageId,
                                          QualityDispositionResult disposition) {
        Map<String, Object> details = skippedProcessingDetails(disposition);
        jdbcTemplate.update(
                "INSERT INTO t_image_action_log " +
                        "(capture_id, image_id, action_type, action_status, reason, details) " +
                        "VALUES (?, ?, 'CORRECT', 'SUCCESS', ?, CAST(? AS jsonb))",
                captureId,
                imageId,
                "当前版本没有可执行的自动修复动作，或无需修复。",
                toJson(details));
    }

    private Map<String, Object> skippedProcessingDetails(QualityDispositionResult disposition) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("processingVersion", SpectralImageProcessingService.PROCESSING_VERSION);
        details.put("processingStatus", "SKIPPED");
        details.put("recommendedActions", disposition == null
                ? Collections.emptyList()
                : disposition.getRecommendedActions());
        details.put("reason", "当前版本没有可执行的自动修复动作，或无需修复。");
        return details;
    }

    private Map<String, Object> qualityToMap(QualityAnalysisResult quality) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (quality == null) {
            return map;
        }
        map.put("qualityStatus", quality.getQualityStatus());
        map.put("summaryMessage", quality.getSummaryMessage());
        map.put("pixelMin", quality.getPixelMin());
        map.put("pixelMax", quality.getPixelMax());
        map.put("pixelMean", quality.getPixelMean());
        map.put("pixelStddev", quality.getPixelStddev());
        map.put("blackPixelRatio", quality.getBlackPixelRatio());
        map.put("saturationPixelRatio", quality.getSaturationPixelRatio());
        map.put("abnormalRowCount", quality.getAbnormalRowCount());
        map.put("abnormalColumnCount", quality.getAbnormalColumnCount());
        map.put("badPixelCount", quality.getBadPixelCount());
        // 不直接返回原始 details 引用：质量审计快照会被嵌入主 details，直接复用会形成 JSON 循环。
        map.put("details", new LinkedHashMap<>(quality.getDetails()));
        return map;
    }

    /**
     * 将原始 RAW 的不可逆风险单独保存。坏点、行列等空间指标会在校准后完整评估；
     * 这里仅保留暗场/饱和/动态范围三项硬性指标，避免校准前的固定图样干扰最终可用性结论。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildRawHardQualitySnapshot(QualityAnalysisResult rawQuality) {
        Map<String, Object> snapshot = qualityToMap(rawQuality);
        Map<String, Object> details = asNullableMap(snapshot.get("details"));
        if (details == null) {
            details = new LinkedHashMap<>();
        }
        Map<String, Object> thresholds = asNullableMap(details.get("thresholds"));
        int dynamicRange = asNullableInteger(details.get("dynamicRangeDn")) == null
                ? rawQuality.getPixelMax() - rawQuality.getPixelMin()
                : asNullableInteger(details.get("dynamicRangeDn"));
        double blackWarning = numberFromMap(thresholds, "blackWarningRatio", 0.05d);
        double blackFail = numberFromMap(thresholds, "blackFailRatio", 0.5d);
        double saturationWarning = numberFromMap(thresholds, "saturationWarningRatio", 0.001d);
        double saturationFail = numberFromMap(thresholds, "saturationFailRatio", 0.01d);
        int rangeWarning = integerFromMap(thresholds, "dynamicRangeWarningDn", 64);
        int rangeFail = integerFromMap(thresholds, "dynamicRangeFailDn", 16);

        boolean fail = false;
        boolean warning = false;
        List<String> reasons = new ArrayList<>();
        if (rawQuality.getBlackPixelRatio() >= blackFail) {
            fail = true;
            reasons.add("原始RAW黑场/低端裁剪比例达到FAIL阈值");
        } else if (rawQuality.getBlackPixelRatio() >= blackWarning) {
            warning = true;
            reasons.add("原始RAW黑场/低端裁剪比例达到WARNING阈值");
        }
        if (rawQuality.getSaturationPixelRatio() >= saturationFail) {
            fail = true;
            reasons.add("原始RAW饱和比例达到FAIL阈值");
        } else if (rawQuality.getSaturationPixelRatio() >= saturationWarning) {
            warning = true;
            reasons.add("原始RAW饱和比例达到WARNING阈值");
        }
        if (dynamicRange <= rangeFail) {
            fail = true;
            reasons.add("原始RAW动态范围过小，接近常数图");
        } else if (dynamicRange <= rangeWarning) {
            warning = true;
            reasons.add("原始RAW动态范围偏小");
        }
        if (reasons.isEmpty()) {
            reasons.add("原始RAW硬性曝光与动态范围检查通过");
        }
        String status = fail ? "FAIL" : (warning ? "WARNING" : "PASS");
        details.put("evaluationStage", "RAW_HARD_CHECK");
        details.put("displayMetricKeys", Arrays.asList("dynamicRange", "blackPixelRatio", "saturationPixelRatio"));
        details.put("decisionReasons", reasons);
        snapshot.put("qualityStatus", status);
        snapshot.put("summaryMessage", "原始RAW硬性检查" + status + ": " + String.join("；", reasons));
        snapshot.put("details", details);
        return snapshot;
    }

    private void attachCalibrationQualityAudit(QualityAnalysisResult primaryQuality,
                                               Map<String, Object> calibrationDetails,
                                               Map<String, Object> rawHardQuality,
                                               Map<String, Object> calibratedQuality) {
        primaryQuality.getDetails().put("calibration", new LinkedHashMap<>(calibrationDetails));
        primaryQuality.getDetails().put("rawHardQuality", rawHardQuality);
        if (calibratedQuality != null) {
            primaryQuality.getDetails().put("calibratedQuality", calibratedQuality);
            primaryQuality.getDetails().put("evaluationStage", "CALIBRATED_FULL_QC");
        } else {
            primaryQuality.getDetails().put("evaluationStage", "RAW_FULL_QC");
        }
    }

    @SuppressWarnings("unchecked")
    private QualityAnalysisResult applyRawHardQualityGate(QualityAnalysisResult primaryQuality,
                                                          Map<String, Object> rawHardQuality) {
        String rawHardStatus = asNullableString(rawHardQuality.get("qualityStatus"));
        String finalStatus = moreSevereQualityStatus(primaryQuality.getQualityStatus(), rawHardStatus);
        primaryQuality.getDetails().put("rawHardQualityGateStatus", rawHardStatus);
        primaryQuality.getDetails().put("finalQualityStatus", finalStatus);
        if (finalStatus.equals(primaryQuality.getQualityStatus())) {
            return primaryQuality;
        }

        List<String> rawReasons = Collections.emptyList();
        Map<String, Object> rawDetails = asNullableMap(rawHardQuality.get("details"));
        Object rawReasonValue = rawDetails == null ? null : rawDetails.get("decisionReasons");
        if (rawReasonValue instanceof List) {
            rawReasons = new ArrayList<>();
            for (Object value : (List<?>) rawReasonValue) {
                if (value != null) {
                    rawReasons.add(String.valueOf(value));
                }
            }
        }
        Object primaryReasonValue = primaryQuality.getDetails().get("decisionReasons");
        if (primaryReasonValue instanceof List) {
            List<Object> primaryReasons = (List<Object>) primaryReasonValue;
            for (String rawReason : rawReasons) {
                String prefixed = "原始RAW硬性检查：" + rawReason;
                if (!primaryReasons.contains(prefixed)) {
                    primaryReasons.add(prefixed);
                }
            }
        }
        String summary = primaryQuality.getSummaryMessage() + "；原始RAW硬性检查为" + rawHardStatus
                + "，最终状态按较严重结果记为" + finalStatus;
        return new QualityAnalysisResult(
                finalStatus,
                appendAnalysisVersionSuffix(primaryQuality.getAnalysisVersion(), "+gate"),
                primaryQuality.getPixelMin(),
                primaryQuality.getPixelMax(),
                primaryQuality.getPixelMean(),
                primaryQuality.getPixelStddev(),
                primaryQuality.getBlackPixelRatio(),
                primaryQuality.getSaturationPixelRatio(),
                primaryQuality.getAbnormalRowCount(),
                primaryQuality.getAbnormalColumnCount(),
                primaryQuality.getBadPixelCount(),
                primaryQuality.getDetails(),
                summary);
    }

    private String appendAnalysisVersionSuffix(String analysisVersion, String suffix) {
        String base = analysisVersion == null || analysisVersion.trim().isEmpty()
                ? SpectralImageQualityAnalysisService.ANALYSIS_VERSION
                : analysisVersion;
        String value = base + suffix;
        return value.length() <= 32 ? value : value.substring(0, 32);
    }

    private String moreSevereQualityStatus(String first, String second) {
        return qualitySeverity(second) > qualitySeverity(first) ? second : first;
    }

    private int qualitySeverity(String status) {
        if ("FAIL".equals(status)) {
            return 2;
        }
        if ("WARNING".equals(status)) {
            return 1;
        }
        return 0;
    }

    private double numberFromMap(Map<String, Object> values, String key, double fallback) {
        Object value = values == null ? null : values.get(key);
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    private int integerFromMap(Map<String, Object> values, String key, int fallback) {
        Object value = values == null ? null : values.get(key);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private Map<String, Object> dispositionToMap(QualityDispositionResult disposition) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (disposition == null) {
            return map;
        }
        map.put("dispositionStatus", disposition.getDispositionStatus());
        map.put("usableForSpectral", disposition.isUsableForSpectral());
        map.put("summaryMessage", disposition.getSummaryMessage());
        map.put("recommendedActions", disposition.getRecommendedActions());
        map.put("reasonCodes", disposition.getReasonCodes());
        return map;
    }

    /**
     * 保存没有生成有效图片的失败结果，例如FPGA错误、接收超时或CRC不匹配。
     */
    @Transactional
    public void saveFailedCapture(long captureId,
                                  String captureStatus,
                                  String resultCode,
                                  String message,
                                  Integer fpgaErrorCode,
                                  Long elapsedMs,
                                  Map<String, Object> details) {
        jdbcTemplate.update(
                "INSERT INTO t_image_integrity_analysis " +
                        "(capture_id, passed, result_code, result_message, elapsed_ms, details) " +
                        "VALUES (?, FALSE, ?, ?, ?, CAST(? AS jsonb)) " +
                        "ON CONFLICT (capture_id) DO UPDATE SET " +
                        "passed=FALSE, result_code=EXCLUDED.result_code, result_message=EXCLUDED.result_message, " +
                        "elapsed_ms=EXCLUDED.elapsed_ms, details=EXCLUDED.details",
                captureId,
                resultCode,
                message,
                elapsedMs,
                toJson(details == null ? Collections.emptyMap() : details));

        jdbcTemplate.update(
                "UPDATE t_spectral_capture SET capture_status=?, completed_at=CURRENT_TIMESTAMP, " +
                        "fpga_error_code=?, error_message=? WHERE id=?",
                captureStatus,
                fpgaErrorCode,
                message,
                captureId);
    }

    /**
     * 查询当前用户最近的数据库历史图片。
     * 预览图在查询时从文件读取并编码，浏览器刷新后仍能从数据库恢复历史记录。
     */
    public List<ImageFrameResponse> listImages(Long userId) {
        return listImagesByScene(userId, "NORMAL");
    }

    public List<ImageFrameResponse> listHdrImages(Long userId) {
        return listImagesByScene(userId, "HDR");
    }

    public List<ImageFrameResponse> listHdrDarkImages(Long userId) {
        return listImagesByScene(userId, "HDR_DARK");
    }

    public List<ImageFrameResponse> listHdrFlatImages(Long userId) {
        return listImagesByScene(userId, "HDR_FLAT");
    }

    private List<ImageFrameResponse> listImagesByScene(Long userId, String captureScene) {
        String normalizedCaptureScene = normalizeCaptureScene(captureScene);
        List<StoredImageRow> rows = jdbcTemplate.query(
                "SELECT i.id AS image_id, c.id AS capture_id, c.request_id, i.received_at, " +
                        "c.capture_scene, " +
                        "i.width, i.height, i.payload_length, i.pixel_format, " +
                        "i.fpga_payload_storage_uri, i.fpga_payload_sha256, i.readout_order, " +
                        "i.hg_raw_storage_uri, i.lg_raw_storage_uri, " +
                        "i.hg_preview_storage_uri, i.lg_preview_storage_uri, " +
                        "i.hdr_fusion_mask_storage_uri, i.hdr_gain_ratio, " +
                        "i.hdr_fusion_details::text AS hdr_fusion_details_json, " +
                        "i.preview_storage_uri, " +
                        "i.calibrated_preview_storage_uri, i.processed_storage_uri, " +
                        "ia.passed, ia.result_code, qa.quality_status, qa.pixel_min, qa.pixel_max, " +
                        "qa.pixel_mean, qa.pixel_stddev, qa.black_pixel_ratio, qa.saturation_pixel_ratio, " +
                        "qa.abnormal_row_count, qa.abnormal_column_count, qa.bad_pixel_count, " +
                        "qa.disposition_status, qa.usable_for_spectral, qa.disposition_message, " +
                        "qa.recommended_actions, qa.details AS quality_details, " +
                        "COALESCE(pa.details->>'processingStatus', pa.action_status) AS processing_status, " +
                        "pa.reason AS processing_message, " +
                        "pa.details AS processing_details " +
                        "FROM t_spectral_capture c " +
                        "JOIN t_spectral_image i ON i.capture_id=c.id " +
                        "LEFT JOIN t_image_integrity_analysis ia ON ia.capture_id=c.id " +
                        "LEFT JOIN t_image_quality_analysis qa ON qa.image_id=i.id " +
                        "LEFT JOIN LATERAL ( " +
                        "    SELECT al.action_status, al.reason, al.details " +
                        "    FROM t_image_action_log al " +
                        "    WHERE al.image_id=i.id AND al.action_type='CORRECT' " +
                        "    ORDER BY al.created_at DESC LIMIT 1 " +
                        ") pa ON TRUE " +
                        "WHERE c.user_id=? AND c.capture_scene=? ORDER BY i.received_at DESC LIMIT ?",
                (resultSet, rowNum) -> mapStoredImageRow(resultSet),
                userId,
                normalizedCaptureScene,
                MAX_HISTORY_SIZE);

        List<ImageFrameResponse> responses = new ArrayList<>();
        for (StoredImageRow row : rows) {
            responses.add(toResponse(row));
        }
        return responses;
    }

    /**
     * 按需读取重排后 RAW16 像素窗口。
     *
     * <p>注意：前端预览图是 8-bit PNG，只用于观察形态；这里读取的是磁盘上的
     * raw16le.bin，因此返回的是真正参与质量分析、图像处理和光谱提取的 RAW16 数据。
     * 对 GLUX1605BSI HDR 读出而言，raw16le.bin 已经由 native 按 Figure 42 的
     * 4-lane 有效像素顺序重排成正常行列坐标；它不是 FPGA 直接发来的 fpga_payload.bin。
     * 默认窗口限制在 64x64 以内；用户明确请求 fullFrame 时，允许一次返回整幅图像的
     * 十六进制字节矩阵。</p>
     */
    public ImagePixelDataResponse getImagePixels(Long userId,
                                                 long imageId,
                                                 String sourceMode,
                                                 String displayFormat,
                                                 boolean fullFrame,
                                                 Integer xStart,
                                                 Integer yStart,
                                                 Integer windowWidth,
                                                 Integer windowHeight) {
        List<PixelSourceRow> rows = jdbcTemplate.query(
                "SELECT i.id AS image_id, i.width, i.height, i.pixel_format, i.readout_order, " +
                        "i.raw_storage_uri, " +
                        "i.calibrated_raw_storage_uri, " +
                        "pa.details::text AS processing_details_json " +
                        "FROM t_spectral_image i " +
                        "JOIN t_spectral_capture c ON c.id=i.capture_id " +
                        "LEFT JOIN LATERAL ( " +
                        "    SELECT al.details " +
                        "    FROM t_image_action_log al " +
                        "    WHERE al.image_id=i.id AND al.action_type='CORRECT' " +
                        "    ORDER BY al.created_at DESC LIMIT 1 " +
                        ") pa ON TRUE " +
                        "WHERE i.id=? AND c.user_id=? AND c.capture_scene IN ('NORMAL','HDR','HDR_DARK','HDR_FLAT')",
                (resultSet, rowNum) -> {
                    PixelSourceRow row = new PixelSourceRow();
                    row.imageId = resultSet.getLong("image_id");
                    row.width = resultSet.getInt("width");
                    row.height = resultSet.getInt("height");
                    row.pixelFormat = resultSet.getString("pixel_format");
                    row.readoutOrder = resultSet.getString("readout_order");
                    row.rawStorageUri = resultSet.getString("raw_storage_uri");
                    row.calibratedRawStorageUri = resultSet.getString("calibrated_raw_storage_uri");
                    row.processingDetails = parseJsonMap(resultSet.getString("processing_details_json"));
                    return row;
                },
                imageId,
                userId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("图像不存在或不属于当前用户");
        }

        PixelSourceRow source = rows.get(0);
        if (source.width <= 0 || source.height <= 0) {
            throw new IllegalStateException("图像尺寸异常，无法读取像素");
        }

        String normalizedSource = sourceMode == null ? "ORIGINAL" : sourceMode.trim().toUpperCase();
        if (!"ORIGINAL".equals(normalizedSource)
                && !"CALIBRATED".equals(normalizedSource)
                && !"PROCESSED".equals(normalizedSource)) {
            throw new IllegalArgumentException("source 只能是 ORIGINAL、CALIBRATED 或 PROCESSED");
        }

        String normalizedFormat = displayFormat == null ? "DN" : displayFormat.trim().toUpperCase();
        if (!"DN".equals(normalizedFormat)
                && !"HEX_WORD".equals(normalizedFormat)
                && !"HEX_FILE".equals(normalizedFormat)) {
            throw new IllegalArgumentException("format 只能是 DN、HEX_WORD 或 HEX_FILE");
        }

        String rawUri = source.rawStorageUri;
        if ("CALIBRATED".equals(normalizedSource)) {
            rawUri = source.calibratedRawStorageUri;
            if (rawUri == null || rawUri.trim().isEmpty()) {
                throw new IllegalStateException("当前图像还没有校准后RAW像素，请先启用校准包并重新采集。");
            }
        } else if ("PROCESSED".equals(normalizedSource)) {
            rawUri = asNullableString(source.processingDetails.get("processedRawStorageUri"));
            if (rawUri == null || rawUri.trim().isEmpty()) {
                throw new IllegalStateException("当前图像还没有处理后RAW像素，请先完成图像处理");
            }
        }

        int x = fullFrame ? 0 : clampPixelWindowStart(xStart, source.width);
        int y = fullFrame ? 0 : clampPixelWindowStart(yStart, source.height);
        int roiWidth = fullFrame ? source.width : clampPixelWindowSize(windowWidth, source.width - x);
        int roiHeight = fullFrame ? source.height : clampPixelWindowSize(windowHeight, source.height - y);

        byte[] rawBytes;
        try {
            rawBytes = readRaw16LeBytes(resolveStorageUri(rawUri), source.width * source.height);
        } catch (IOException ex) {
            throw new IllegalStateException("读取RAW16像素失败: " + ex.getMessage(), ex);
        }

        int pixelMin = Integer.MAX_VALUE;
        int pixelMax = Integer.MIN_VALUE;
        long pixelSum = 0L;
        List<List<Integer>> pixelRows = new ArrayList<>();
        List<String> hexRows = new ArrayList<>();
        boolean hexFormat = normalizedFormat.startsWith("HEX");
        for (int rowIndex = 0; rowIndex < roiHeight; rowIndex++) {
            List<Integer> pixelRow = hexFormat ? Collections.emptyList() : new ArrayList<>();
            StringBuilder hexRow = hexFormat ? new StringBuilder(roiWidth * 7) : null;
            int sourceOffset = (y + rowIndex) * source.width + x;
            for (int columnIndex = 0; columnIndex < roiWidth; columnIndex++) {
                int pixelIndex = sourceOffset + columnIndex;
                int byteIndex = pixelIndex * Short.BYTES;
                int fileLowByte = rawBytes[byteIndex] & 0xFF;
                int fileHighByte = rawBytes[byteIndex + 1] & 0xFF;
                int value = fileLowByte | (fileHighByte << 8);
                pixelMin = Math.min(pixelMin, value);
                pixelMax = Math.max(pixelMax, value);
                pixelSum += value;
                if (hexFormat) {
                    if (columnIndex > 0) {
                        // 两个空格分隔相邻像素；一个像素内部仍保持“两个字节”形态，例如 00 A0。
                        hexRow.append("  ");
                    }
                    if ("HEX_FILE".equals(normalizedFormat)) {
                        // RAW16LE 文件真实落盘顺序：低字节在前，高字节在后。
                        hexRow.append(toHexByte(fileLowByte)).append(' ').append(toHexByte(fileHighByte));
                    } else {
                        // 人读的 16 位字值显示：高字节在前。DN=0x00A0 时显示为 00 A0。
                        hexRow.append(toHexByte(fileHighByte)).append(' ').append(toHexByte(fileLowByte));
                    }
                } else {
                    pixelRow.add(value & 0x0FFF);
                }
            }
            if (hexFormat) {
                hexRows.add(hexRow.toString());
            } else {
                pixelRows.add(pixelRow);
            }
        }

        ImagePixelDataResponse response = new ImagePixelDataResponse();
        String responseReadoutOrder = source.readoutOrder == null ? READOUT_ORDER : source.readoutOrder;
        response.setImageId(source.imageId);
        response.setSourceMode(normalizedSource);
        response.setSourceLabel(pixelSourceLabel(normalizedSource, responseReadoutOrder));
        response.setSpatialOrder(isRowMajorReadout(responseReadoutOrder)
                ? "ROW_MAJOR_NORMAL_IMAGE_ORDER"
                : "ROW_MAJOR_AFTER_SENSOR_READOUT_REORDER");
        response.setReadoutOrder(responseReadoutOrder);
        response.setSourceDescription(pixelSourceDescription(normalizedSource, responseReadoutOrder));
        response.setWidth(source.width);
        response.setHeight(source.height);
        response.setXStart(x);
        response.setYStart(y);
        response.setXEnd(x + roiWidth);
        response.setYEnd(y + roiHeight);
        response.setRoiWidth(roiWidth);
        response.setRoiHeight(roiHeight);
        response.setStorageBitDepth(16);
        response.setEffectiveBitDepth(12);
        response.setPixelFormat(source.pixelFormat == null ? PIXEL_FORMAT : source.pixelFormat);
        response.setDisplayFormat(normalizedFormat);
        response.setRawFileByteOrder("LITTLE_ENDIAN");
        response.setFullFrame(fullFrame);
        response.setPixelMin(pixelMin == Integer.MAX_VALUE ? 0 : pixelMin);
        response.setPixelMax(pixelMax == Integer.MIN_VALUE ? 0 : pixelMax);
        response.setPixelMean(roiWidth * roiHeight == 0 ? 0.0d : (double) pixelSum / (roiWidth * roiHeight));
        response.setRows(hexFormat ? Collections.emptyList() : pixelRows);
        response.setHexRows(hexRows);
        return response;
    }

    /**
     * 按 FPGA 原始 payload 线性顺序读取像素窗口。
     *
     * <p>这个方法读取的是 fpga_payload.bin，不是 raw16le.bin。返回结果中的 payloadColumn
     * 表示当前行内 FPGA 发出的顺序位置；imageX/imageY 表示 native 按 GLUX1605BSI HDR
     * 4-lane 有效像素顺序重排后落到的正常图像坐标。</p>
     */
    public FpgaPayloadPixelDataResponse getFpgaPayloadPixels(Long userId,
                                                             long imageId,
                                                             Integer start,
                                                             Integer count,
                                                             boolean fullFrame) {
        List<FpgaPayloadSourceRow> rows = jdbcTemplate.query(
                "SELECT i.id AS image_id, c.capture_scene, i.width, i.height, i.pixel_format, i.payload_length, " +
                        "i.fpga_payload_storage_uri, i.fpga_payload_sha256, i.readout_order " +
                        "FROM t_spectral_image i " +
                        "JOIN t_spectral_capture c ON c.id=i.capture_id " +
                        "WHERE i.id=? AND c.user_id=? AND c.capture_scene IN ('NORMAL','HDR','HDR_DARK','HDR_FLAT')",
                (resultSet, rowNum) -> {
                    FpgaPayloadSourceRow row = new FpgaPayloadSourceRow();
                    row.imageId = resultSet.getLong("image_id");
                    row.captureScene = resultSet.getString("capture_scene");
                    row.width = resultSet.getInt("width");
                    row.height = resultSet.getInt("height");
                    row.pixelFormat = resultSet.getString("pixel_format");
                    row.payloadLength = resultSet.getLong("payload_length");
                    row.payloadStorageUri = resultSet.getString("fpga_payload_storage_uri");
                    row.payloadSha256 = resultSet.getString("fpga_payload_sha256");
                    row.readoutOrder = resultSet.getString("readout_order");
                    return row;
                },
                imageId,
                userId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("图像不存在或不属于当前用户");
        }

        FpgaPayloadSourceRow source = rows.get(0);
        if (source.payloadStorageUri == null || source.payloadStorageUri.trim().isEmpty()) {
            throw new IllegalStateException("当前图像没有保存FPGA原始payload，请重新采集新帧。");
        }
        if (source.width <= 0 || source.height <= 0) {
            throw new IllegalStateException("图像尺寸异常，无法读取FPGA原始payload");
        }

        long planePixelCountLong = (long) source.width * source.height;
        if (planePixelCountLong <= 0 || planePixelCountLong > Integer.MAX_VALUE) {
            throw new IllegalStateException("图像像素数过大，无法在当前接口中查看FPGA原始payload");
        }
        if ((source.payloadLength % Short.BYTES) != 0L) {
            throw new IllegalStateException("FPGA原始payload长度不是2字节像素的整数倍");
        }
        long payloadPixelCountLong = source.payloadLength / Short.BYTES;
        if (payloadPixelCountLong > Integer.MAX_VALUE) {
            throw new IllegalStateException("FPGA原始payload像素数过大，无法在当前接口中查看");
        }
        int planePixelCount = (int) planePixelCountLong;
        int payloadPixelCount = (int) payloadPixelCountLong;
        int payloadPlaneCount = Math.max(1, payloadPixelCount / planePixelCount);
        if (payloadPixelCount != planePixelCount * payloadPlaneCount) {
            throw new IllegalStateException("FPGA原始payload长度与图像平面尺寸不匹配");
        }
        int payloadStart = fullFrame ? 0 : clampPayloadWindowStart(start, payloadPixelCount);
        int requestedCount = fullFrame
                ? payloadPixelCount
                : clampPayloadWindowCount(count, payloadPixelCount - payloadStart);
        int payloadEnd = payloadStart + requestedCount;

        byte[] payloadBytes;
        try {
            payloadBytes = readRaw16LeBytes(resolveStorageUri(source.payloadStorageUri), payloadPixelCount);
        } catch (IOException ex) {
            throw new IllegalStateException("读取FPGA原始payload失败: " + ex.getMessage(), ex);
        }

        String readoutOrder = source.readoutOrder == null ? READOUT_ORDER : source.readoutOrder;
        boolean gluxFourLane = isGlux1605Hdr4LaneReadout(readoutOrder) && source.width % 4 == 0;
        int laneCount = gluxFourLane ? 4 : 1;
        int laneWidth = gluxFourLane ? source.width / laneCount : source.width;

        int pixelMin = Integer.MAX_VALUE;
        int pixelMax = Integer.MIN_VALUE;
        long pixelSum = 0L;
        List<FpgaPayloadPixelDataResponse.PixelRecord> records = new ArrayList<>();
        List<String> hexRows = fullFrame ? new ArrayList<>() : Collections.emptyList();
        StringBuilder hexRow = null;
        int currentHexRow = -1;
        for (int payloadIndex = payloadStart; payloadIndex < payloadEnd; payloadIndex++) {
            int byteIndex = payloadIndex * Short.BYTES;
            int fileLowByte = payloadBytes[byteIndex] & 0xFF;
            int fileHighByte = payloadBytes[byteIndex + 1] & 0xFF;
            int raw16Value = fileLowByte | (fileHighByte << 8);
            int dn = raw16Value & 0x0FFF;
            pixelMin = Math.min(pixelMin, dn);
            pixelMax = Math.max(pixelMax, dn);
            pixelSum += dn;

            int planeIndex = payloadIndex / planePixelCount;
            int planePixelIndex = payloadIndex % planePixelCount;
            int payloadRow = planePixelIndex / source.width;
            int payloadColumn = planePixelIndex % source.width;
            int lane = gluxFourLane ? payloadColumn % laneCount : 0;
            int sample = gluxFourLane ? payloadColumn / laneCount : payloadColumn;
            int imageX = gluxFourLane ? lane * laneWidth + sample : payloadColumn;
            int imageY = payloadRow;

            if (fullFrame) {
                if (payloadRow != currentHexRow) {
                    if (hexRow != null) {
                        hexRows.add(hexRow.toString());
                    }
                    hexRow = new StringBuilder(source.width * 7);
                    currentHexRow = payloadRow;
                } else if (hexRow != null) {
                    hexRow.append("  ");
                }
                if (hexRow != null) {
                    hexRow.append(toHexByte(fileHighByte)).append(' ').append(toHexByte(fileLowByte));
                }
            } else {
                FpgaPayloadPixelDataResponse.PixelRecord record =
                        new FpgaPayloadPixelDataResponse.PixelRecord();
                record.setPayloadIndex(payloadIndex);
                record.setPlane(payloadPlaneName(source.captureScene, planeIndex));
                record.setPlanePixelIndex(planePixelIndex);
                record.setPayloadRow(payloadRow);
                record.setPayloadColumn(payloadColumn);
                record.setLane(lane);
                record.setSample(sample);
                record.setImageX(imageX);
                record.setImageY(imageY);
                record.setHexWord(toHexByte(fileHighByte) + " " + toHexByte(fileLowByte));
                record.setHexFileBytes(toHexByte(fileLowByte) + " " + toHexByte(fileHighByte));
                record.setDn(dn);
                record.setRaw16Value(raw16Value);
                records.add(record);
            }
        }
        if (fullFrame && hexRow != null) {
            hexRows.add(hexRow.toString());
        }

        FpgaPayloadPixelDataResponse response = new FpgaPayloadPixelDataResponse();
        response.setImageId(source.imageId);
        response.setWidth(source.width);
        response.setHeight(source.height);
        response.setPixelFormat(source.pixelFormat == null ? PIXEL_FORMAT : source.pixelFormat);
        response.setReadoutOrder(readoutOrder);
        response.setPayloadStorageUri(source.payloadStorageUri);
        response.setPayloadSha256(source.payloadSha256);
        response.setRawFileByteOrder("LITTLE_ENDIAN");
        response.setStorageBitDepth(16);
        response.setEffectiveBitDepth(12);
        response.setLaneCount(laneCount);
        response.setLaneWidth(laneWidth);
        response.setPayloadPlaneCount(payloadPlaneCount);
        response.setPayloadPixelCount(payloadPixelCount);
        response.setPayloadStart(payloadStart);
        response.setPayloadEnd(payloadEnd);
        response.setReturnedCount(fullFrame ? requestedCount : records.size());
        response.setMaxWindowCount(MAX_FPGA_PAYLOAD_WINDOW_SIZE);
        response.setFullFrame(fullFrame);
        response.setDisplayFormat("HEX_WORD");
        response.setPixelMin(pixelMin == Integer.MAX_VALUE ? 0 : pixelMin);
        response.setPixelMax(pixelMax == Integer.MIN_VALUE ? 0 : pixelMax);
        response.setPixelMean(requestedCount == 0 ? 0.0d : (double) pixelSum / requestedCount);
        boolean rowMajorReadout = isRowMajorReadout(readoutOrder);
        response.setSourceDescription(
                "当前数据来自 fpga_payload.bin，是 FPGA 直接输出的有效像素顺序；"
                        + (isHdrCaptureScene(source.captureScene)
                        ? ("HDR_DARK".equals(normalizeCaptureScene(source.captureScene))
                                ? "HDR暗场payload包含两个完整平面，顺序为HG暗场平面后接LG暗场平面；"
                                : "HDR_FLAT".equals(normalizeCaptureScene(source.captureScene))
                                ? "HDR平场payload包含两个完整平面，顺序为HG平场平面后接LG平场平面；"
                                : "HDR payload包含两个完整平面，顺序为HG平面后接LG平面；")
                        : "")
                        + (rowMajorReadout
                        ? "当前读出顺序为 ROW_MAJOR，payloadRow/payloadColumn 就是正常图像 y/x 坐标，不需要空间重排。"
                        : "payloadColumn 是当前平面行内原始发送位置，imageX/imageY 是按 " + readoutOrder
                                + " 重排后对应的正常图像坐标。"));
        response.setHexRows(hexRows);
        response.setPixels(records);
        return response;
    }

    /**
     * 对历史图片手动执行当前阶段可用的图像处理。
     *
     * <p>该方法只处理当前登录用户自己的图片；处理时重新读取原始 RAW16，
     * 因而不会受到预览图或前端显示压缩的影响。</p>
     */
    @Transactional
    public ImageFrameResponse processImage(Long userId, long imageId) {
        List<ProcessSourceRow> sources = jdbcTemplate.query(
                "SELECT i.id AS image_id, i.capture_id, i.width, i.height, i.raw_storage_uri, " +
                        "i.calibrated_raw_storage_uri, i.processed_storage_uri, qa.details::text AS quality_details_json " +
                "FROM t_spectral_image i JOIN t_spectral_capture c ON c.id=i.capture_id " +
                        "LEFT JOIN t_image_quality_analysis qa ON qa.image_id=i.id " +
                        "WHERE i.id=? AND c.user_id=? AND c.capture_scene IN ('NORMAL','HDR')",
                (resultSet, rowNum) -> {
                    ProcessSourceRow row = new ProcessSourceRow();
                    row.imageId = resultSet.getLong("image_id");
                    row.captureId = resultSet.getLong("capture_id");
                    row.width = resultSet.getInt("width");
                    row.height = resultSet.getInt("height");
                    row.rawStorageUri = resultSet.getString("raw_storage_uri");
                    row.calibratedRawStorageUri = resultSet.getString("calibrated_raw_storage_uri");
                    row.processedStorageUri = resultSet.getString("processed_storage_uri");
                    row.qualityDetails = parseJsonMap(resultSet.getString("quality_details_json"));
                    return row;
                },
                imageId,
                userId);
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("图像不存在或不属于当前用户");
        }

        ProcessSourceRow source = sources.get(0);
        if (source.processedStorageUri != null && !source.processedStorageUri.trim().isEmpty()) {
            throw new IllegalStateException("当前图片已完成图像处理，无需重复处理");
        }
        Path rawFile = resolveStorageUri(source.rawStorageUri);
        try {
            short[] pixels16;
            Map<String, Object> calibrationDetails;
            if (source.calibratedRawStorageUri != null && !source.calibratedRawStorageUri.trim().isEmpty()) {
                pixels16 = readRaw16Le(resolveStorageUri(source.calibratedRawStorageUri), source.width * source.height);
                Map<String, Object> capturedCalibration = asNullableMap(source.qualityDetails.get("calibration"));
                calibrationDetails = capturedCalibration == null ? new LinkedHashMap<>() : capturedCalibration;
                calibrationDetails.put("preprocessingApplied", true);
                calibrationDetails.put("calibratedRawStorageUri", source.calibratedRawStorageUri);
            } else {
                short[] rawPixels16 = readRaw16Le(rawFile, source.width * source.height);
                // 兼容旧数据：没有落盘 calibrated RAW 时，按该图片采集时锁定的校准包恢复同一输入版本。
                SpectralCalibrationService.CalibrationProfile calibrationProfile =
                        calibrationService.loadCapturedProfile(userId, source.width, source.height, source.qualityDetails);
                PreprocessingResult preprocessing = applyCalibrationPackage(calibrationProfile, rawPixels16);
                pixels16 = preprocessing.pixels16;
                calibrationDetails = new LinkedHashMap<>(preprocessing.calibrationDetails);
            }
            QualityAnalysisResult quality = qualityAnalysisService.analyze(source.width, source.height, pixels16);
            quality.getDetails().put("calibration", calibrationDetails);
            QualityDispositionResult disposition = qualityDispositionService.decide(quality);
            SavedProcessingResult processing = tryProcessAndSaveImage(
                    source.captureId,
                    source.imageId,
                    source.width,
                    source.height,
                    rawFile.getParent(),
                    pixels16,
                    quality,
                    disposition,
                    calibrationDetails);
            if (processing == null) {
                saveSkippedProcessingLog(source.captureId, source.imageId, disposition);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("读取原始RAW图像失败: " + ex.getMessage(), ex);
        }

        for (ImageFrameResponse response : listImages(userId)) {
            if (response.getId() != null && response.getId() == imageId) {
                return response;
            }
        }
        for (ImageFrameResponse response : listHdrImages(userId)) {
            if (response.getId() != null && response.getId() == imageId) {
                return response;
            }
        }
        throw new IllegalStateException("图像处理后未能重新加载记录");
    }

    /**
     * 使用多个已保存图像构建多帧缺陷地图，并按需修复这些图像。
     * 普通图像的单帧质量结果保持不变；多帧结果通过接口返回，处理结果仍写入原有
     * processed RAW/preview 和动作日志字段。
     */
    @Transactional
    public MultiFrameAnalysisResponse analyzeMultiFrame(Long userId,
                                                        List<Long> imageIds,
                                                        boolean repair,
                                                        double voteRatio) {
        if (imageIds == null || imageIds.size() < 2 || imageIds.size() > 64) {
            throw new IllegalArgumentException("多帧检测需要2到64张图像");
        }
        List<ProcessSourceRow> sources = findProcessSources(userId, imageIds);
        if (sources.size() != imageIds.size()) {
            throw new IllegalArgumentException("部分图像不存在或不属于当前用户");
        }
        int width = sources.get(0).width;
        int height = sources.get(0).height;
        List<short[]> frames = new ArrayList<>();
        // 多帧“测试检测”仅做校准包中的暗场/平场预校正，不把已有缺陷地图套回检测，
        // 避免用旧地图掩盖新的候选缺陷。
        SpectralCalibrationService.CalibrationProfile calibrationProfile =
                calibrationService.loadActiveProfile(userId, width, height);
        try {
            for (ProcessSourceRow source : sources) {
                if (source.width != width || source.height != height) {
                    throw new IllegalArgumentException("多帧检测图像尺寸必须一致");
                }
                short[] rawPixels = readRaw16Le(resolveStorageUri(source.rawStorageUri), width * height);
                frames.add(calibrationProfile.apply(rawPixels).getPixels16());
            }
        } catch (IOException ex) {
            throw new IllegalStateException("读取多帧RAW图像失败: " + ex.getMessage(), ex);
        }

        SpectralMultiFrameQualityAnalysisService.MultiFrameResult result =
                multiFrameQualityAnalysisService.analyze(width, height, frames, voteRatio);

        MultiFrameAnalysisResponse response = new MultiFrameAnalysisResponse();
        response.setFrameCount(result.getFrameCount());
        response.setWidth(width);
        response.setHeight(height);
        response.setVoteRatio(result.getVoteRatio());
        response.setBadPixelCount(result.getBadPixelIndexes().size());
        response.setAbnormalRowCount(result.getAbnormalRows().size());
        response.setAbnormalColumnCount(result.getAbnormalColumns().size());
        response.setBadPixelIndexes(result.getBadPixelIndexes());
        response.setAbnormalRows(result.getAbnormalRows());
        response.setAbnormalColumns(result.getAbnormalColumns());
        response.setAnalyzedImageIds(new ArrayList<>(imageIds));
        response.setSummaryMessage(result.getSummaryMessage());
        Map<String, Object> multiFrameDetails = new LinkedHashMap<>(result.getDetails());
        Map<String, Object> multiFrameCalibrationDetails = new LinkedHashMap<>(calibrationProfile.getDetails());
        multiFrameCalibrationDetails.put("defectMapApplied", false);
        multiFrameCalibrationDetails.put("defectMapMessage", "多帧检测阶段不套用已有缺陷地图，避免掩盖新的持续异常");
        multiFrameDetails.put("calibration", multiFrameCalibrationDetails);
        response.setDetails(multiFrameDetails);

        if (repair) {
            List<Long> repaired = new ArrayList<>();
            for (ProcessSourceRow source : sources) {
                if (source.processedStorageUri != null && !source.processedStorageUri.trim().isEmpty()) {
                    continue;
                }
                if (saveMultiFrameProcessedImage(source, result.getDefectMap(), calibrationProfile)) {
                    repaired.add(source.imageId);
                }
            }
            response.setRepairedImageIds(repaired);
        }
        response.setFrames(listImages(userId));
        if (repair) {
            response.setSummaryMessage(result.getSummaryMessage() + "；已修复 "
                    + response.getRepairedImageIds().size() + " 张图像");
        }
        return response;
    }

    /** 将已保存的暗场/平场缺陷地图应用到一张图像。 */
    @Transactional
    public ImageFrameResponse processImageWithDefectMap(
            Long userId,
            long imageId,
            SpectralMultiFrameQualityAnalysisService.DefectMap defectMap) {
        List<ProcessSourceRow> sources = findProcessSources(
                userId,
                Collections.singletonList(imageId));
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("图像不存在或不属于当前用户");
        }
        ProcessSourceRow source = sources.get(0);
        if (source.processedStorageUri == null || source.processedStorageUri.trim().isEmpty()) {
            SpectralCalibrationService.CalibrationProfile calibrationProfile =
                    calibrationService.loadActiveProfile(userId, source.width, source.height);
            saveMultiFrameProcessedImage(source, defectMap, calibrationProfile);
        }
        for (ImageFrameResponse response : listImages(userId)) {
            if (response.getId() != null && response.getId() == imageId) {
                return response;
            }
        }
        throw new IllegalStateException("应用校准地图后未能重新加载图像");
    }

    private List<ProcessSourceRow> findProcessSources(Long userId, List<Long> imageIds) {
        String placeholders = String.join(",", Collections.nCopies(imageIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.addAll(imageIds);
        return jdbcTemplate.query(
                "SELECT i.id AS image_id, i.capture_id, i.width, i.height, i.raw_storage_uri, " +
                        "i.calibrated_raw_storage_uri, i.processed_storage_uri, " +
                        "qa.details::text AS quality_details_json FROM t_spectral_image i " +
                        "JOIN t_spectral_capture c ON c.id=i.capture_id " +
                        "LEFT JOIN t_image_quality_analysis qa ON qa.image_id=i.id " +
                        "WHERE c.user_id=? AND c.capture_scene='NORMAL' " +
                        "AND i.id IN (" + placeholders + ") ORDER BY i.received_at ASC",
                (resultSet, rowNum) -> {
                    ProcessSourceRow row = new ProcessSourceRow();
                    row.imageId = resultSet.getLong("image_id");
                    row.captureId = resultSet.getLong("capture_id");
                    row.width = resultSet.getInt("width");
                    row.height = resultSet.getInt("height");
                    row.rawStorageUri = resultSet.getString("raw_storage_uri");
                    row.calibratedRawStorageUri = resultSet.getString("calibrated_raw_storage_uri");
                    row.processedStorageUri = resultSet.getString("processed_storage_uri");
                    row.qualityDetails = parseJsonMap(resultSet.getString("quality_details_json"));
                    return row;
                },
                args.toArray());
    }

    private boolean saveMultiFrameProcessedImage(
            ProcessSourceRow source,
            SpectralMultiFrameQualityAnalysisService.DefectMap defectMap,
            SpectralCalibrationService.CalibrationProfile calibrationProfile) {
        Path rawFile = resolveStorageUri(source.rawStorageUri);
        try {
            short[] rawPixels16 = readRaw16Le(rawFile, source.width * source.height);
            SpectralCalibrationService.CalibrationApplicationResult calibration =
                    calibrationProfile.apply(rawPixels16);
            short[] pixels16 = calibration.getPixels16();
            ProcessingResult result = imageProcessingService.processWithMultiFrameDefectMap(
                    source.width,
                    source.height,
                    pixels16,
                    defectMap);
            if (result == null) {
                return false;
            }

            Path processedDirectory = rawFile.getParent().resolve("processed-multi-frame");
            Files.createDirectories(processedDirectory);
            Path processedRawFile = processedDirectory.resolve("raw16le.bin");
            Path processedPreviewFile = processedDirectory.resolve("preview.png");
            Files.write(processedRawFile, toLittleEndianRawBytes(result.getProcessedPixels16()));
            writePreviewPng(source.width, source.height, toPreviewBytes(result.getProcessedPixels16()), processedPreviewFile);

            String processedPreviewUri = toStorageUri(processedPreviewFile);
            Map<String, Object> details = buildProcessingDetails(result,
                    toStorageUri(processedRawFile),
                    processedPreviewUri,
                    calibration.getDetails());
            details.put("processingMode", "MULTI_FRAME");
            details.put("defectMapVoteRatio", defectMap.getVoteRatio());
            details.put("calibration", calibration.getDetails());
            jdbcTemplate.update(
                    "UPDATE t_spectral_image SET processed_storage_uri=? WHERE id=?",
                    processedPreviewUri,
                    source.imageId);
            jdbcTemplate.update(
                    "INSERT INTO t_image_action_log " +
                            "(capture_id, image_id, action_type, action_status, reason, output_storage_uri, details) " +
                            "VALUES (?, ?, 'CORRECT', 'SUCCESS', ?, ?, CAST(? AS jsonb))",
                    source.captureId,
                    source.imageId,
                    result.getSummaryMessage(),
                    processedPreviewUri,
                    toJson(details));
            return true;
        } catch (IOException ex) {
            throw new IllegalStateException("保存多帧处理结果失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 删除当前用户的一张历史图片。删除采集主记录会通过外键级联删除图像和完整性记录。
     */
    @Transactional
    public boolean deleteImage(Long userId, long imageId) {
        List<String> uris = jdbcTemplate.query(
                "SELECT i.raw_storage_uri, i.preview_storage_uri, i.calibrated_preview_storage_uri, " +
                        "i.processed_storage_uri " +
                        "FROM t_spectral_image i JOIN t_spectral_capture c ON c.id=i.capture_id " +
                        "WHERE i.id=? AND c.user_id=? AND c.capture_scene IN ('NORMAL','HDR','HDR_DARK','HDR_FLAT')",
                (resultSet, rowNum) -> firstNonNullUri(resultSet),
                imageId,
                userId);
        if (uris.isEmpty()) {
            return false;
        }

        int affected = jdbcTemplate.update(
                "DELETE FROM t_spectral_capture c USING t_spectral_image i " +
                        "WHERE i.capture_id=c.id AND i.id=? AND c.user_id=? AND c.capture_scene IN ('NORMAL','HDR','HDR_DARK','HDR_FLAT')",
                imageId,
                userId);
        if (affected > 0) {
            deleteFrameDirectoryByUri(uris.get(0));
        }
        return affected > 0;
    }

    /**
     * 清空当前用户的普通单帧和 HDR 融合图像历史，不影响其他用户，也不删除暗场/平场校准包。
     */
    @Transactional
    public int clearImages(Long userId) {
        List<String> rawUris = jdbcTemplate.query(
                "SELECT DISTINCT i.raw_storage_uri FROM t_spectral_image i " +
                        "JOIN t_spectral_capture c ON c.id=i.capture_id " +
                        "WHERE c.user_id=? AND c.capture_scene IN ('NORMAL','HDR')",
                (resultSet, rowNum) -> resultSet.getString("raw_storage_uri"),
                userId);

        int affected = jdbcTemplate.update(
                "DELETE FROM t_spectral_capture WHERE user_id=? AND capture_scene IN ('NORMAL','HDR')",
                userId);
        for (String uri : rawUris) {
            deleteFrameDirectoryByUri(uri);
        }
        return affected;
    }

    private void validateFrameBuffers(int width, int height, short[] pixels16, byte[] pixels8, byte[] fpgaPayload) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("图像宽高必须大于0");
        }
        long expectedPixels = (long) width * (long) height;
        if (expectedPixels > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("图像像素数量超过Java数组限制");
        }
        if (pixels16 == null || pixels16.length != (int) expectedPixels) {
            throw new IllegalArgumentException("16-bit像素数组长度与图像尺寸不一致");
        }
        if (pixels8 == null || pixels8.length != (int) expectedPixels) {
            throw new IllegalArgumentException("8-bit预览数组长度与图像尺寸不一致");
        }
        long expectedPayloadBytes = expectedPixels * Short.BYTES;
        long hdrPayloadBytes = expectedPayloadBytes * 2L;
        long actualPayloadBytes = fpgaPayload == null ? -1L : fpgaPayload.length;
        if (fpgaPayload == null
                || (actualPayloadBytes != expectedPayloadBytes && actualPayloadBytes != hdrPayloadBytes)) {
            throw new IllegalArgumentException("FPGA原始payload长度与图像尺寸不一致");
        }
    }

    private void validateHdrFrameBuffers(int width,
                                         int height,
                                         short[] hgPixels16,
                                         short[] lgPixels16,
                                         byte[] fpgaPayload) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("HDR图像宽高必须大于0");
        }
        long expectedPixels = (long) width * (long) height;
        if (expectedPixels > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("HDR图像像素数量超过Java数组限制");
        }
        if (hgPixels16 == null || hgPixels16.length != (int) expectedPixels) {
            throw new IllegalArgumentException("HG平面16-bit像素数组长度与图像尺寸不一致");
        }
        if (lgPixels16 == null || lgPixels16.length != (int) expectedPixels) {
            throw new IllegalArgumentException("LG平面16-bit像素数组长度与图像尺寸不一致");
        }
        long expectedPayloadBytes = expectedPixels * Short.BYTES * 2L;
        long actualPayloadBytes = fpgaPayload == null ? -1L : fpgaPayload.length;
        if (fpgaPayload == null || actualPayloadBytes != expectedPayloadBytes) {
            throw new IllegalArgumentException("HDR FPGA原始payload必须等于HG+LG两个RAW16平面长度");
        }
    }

    private HdrFusionResult fuseHdrPlanes(int width, int height, short[] hgPixels16, short[] lgPixels16) {
        HdrGainEstimate gainEstimate = estimateHdrGainRatio(hgPixels16, lgPixels16);
        double gainRatio = gainEstimate.gainRatio;
        short[] fusedPixels16 = new short[hgPixels16.length];
        byte[] fusionMask = new byte[hgPixels16.length];
        int hgPixelCount = 0;
        int lgPixelCount = 0;
        int blendPixelCount = 0;
        int bothSaturatedCount = 0;
        int hgSaturatedCount = 0;
        int lgSaturatedCount = 0;
        int hgBlackCount = 0;
        int lgBlackCount = 0;

        for (int index = 0; index < hgPixels16.length; index++) {
            int hg = hgPixels16[index] & 0x0FFF;
            int lg = lgPixels16[index] & 0x0FFF;
            if (hg >= HDR_RAW12_MAX) {
                hgSaturatedCount++;
            }
            if (lg >= HDR_RAW12_MAX) {
                lgSaturatedCount++;
            }
            if (hg == 0) {
                hgBlackCount++;
            }
            if (lg == 0) {
                lgBlackCount++;
            }
            double lgEquivalent = lg * gainRatio;
            double fused;
            byte mask;
            if (hg >= HDR_RAW12_MAX && lg >= HDR_RAW12_MAX) {
                fused = HDR_RAW12_MAX;
                mask = 3;
                bothSaturatedCount++;
            } else if (hg < HDR_HG_BLEND_START) {
                fused = hg;
                mask = 0;
                hgPixelCount++;
            } else if (hg >= HDR_HG_BLEND_END) {
                fused = lgEquivalent;
                mask = 1;
                lgPixelCount++;
            } else {
                double alpha = (double) (hg - HDR_HG_BLEND_START)
                        / (double) (HDR_HG_BLEND_END - HDR_HG_BLEND_START);
                fused = (1.0d - alpha) * hg + alpha * lgEquivalent;
                mask = 2;
                blendPixelCount++;
            }
            int fused12 = (int) Math.round(Math.max(0.0d, Math.min(HDR_RAW12_MAX, fused)));
            fusedPixels16[index] = (short) fused12;
            fusionMask[index] = mask;
        }

        int totalPixelCount = hgPixels16.length;
        double hgUsedRatio = ratio(hgPixelCount, totalPixelCount);
        double lgUsedRatio = ratio(lgPixelCount, totalPixelCount);
        double blendRatio = ratio(blendPixelCount, totalPixelCount);
        double bothSaturatedRatio = ratio(bothSaturatedCount, totalPixelCount);
        double hgSaturatedRatio = ratio(hgSaturatedCount, totalPixelCount);
        double lgSaturatedRatio = ratio(lgSaturatedCount, totalPixelCount);
        double hgBlackRatio = ratio(hgBlackCount, totalPixelCount);
        double lgBlackRatio = ratio(lgBlackCount, totalPixelCount);
        double gainSampleRatio = ratio(gainEstimate.sampleCount, totalPixelCount);
        double lgCompensationRatio = ratio(lgPixelCount + blendPixelCount, totalPixelCount);

        List<String> diagnosticReasons = new ArrayList<>();
        String hdrDiagnosticStatus = "PASS";
        if (bothSaturatedRatio >= HDR_FAIL_BOTH_SATURATED_RATIO) {
            hdrDiagnosticStatus = "FAIL";
            diagnosticReasons.add("融合后仍存在较多HG/LG双饱和像素，HDR也无法恢复这些区域的真实强度");
        } else if (bothSaturatedRatio >= HDR_WARNING_BOTH_SATURATED_RATIO) {
            hdrDiagnosticStatus = "WARNING";
            diagnosticReasons.add("存在少量HG/LG双饱和像素，这些高亮信息可能已经不可逆丢失");
        }
        if (gainEstimate.fallback) {
            hdrDiagnosticStatus = moreSevereHdrStatus(hdrDiagnosticStatus, "WARNING");
            diagnosticReasons.add("HG/LG共同线性区样本不足，增益比使用默认值，融合定量可靠性降低");
        }
        if (lgSaturatedRatio >= HDR_WARNING_LG_SATURATED_RATIO) {
            hdrDiagnosticStatus = moreSevereHdrStatus(hdrDiagnosticStatus, "WARNING");
            diagnosticReasons.add("LG低增益平面出现明显饱和，说明低增益仍可能不足以承接高亮区域");
        }
        if (hgUsedRatio < HDR_WARNING_HG_USED_RATIO_LOW) {
            hdrDiagnosticStatus = moreSevereHdrStatus(hdrDiagnosticStatus, "WARNING");
            diagnosticReasons.add("大部分像素需要由LG或混合区补偿，HG曝光可能偏高或场景亮区占比过大");
        }
        if (diagnosticReasons.isEmpty()) {
            diagnosticReasons.add("HG/LG融合可靠性检查通过，未发现明显双饱和或增益估计不足问题");
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("hdrVersion", "HDR_HG_LG_FUSION_V1");
        details.put("payloadLayout", "HG_FULL_FRAME_THEN_LG_FULL_FRAME");
        details.put("width", width);
        details.put("height", height);
        details.put("totalPixelCount", totalPixelCount);
        details.put("hdrDiagnosticStatus", hdrDiagnosticStatus);
        details.put("hdrDiagnosticReasons", diagnosticReasons);
        details.put("gainRatio", gainRatio);
        details.put("gainRatioMeaning", "HG_DN / LG_DN median estimated from unsaturated common-linear pixels");
        details.put("gainRatioFallback", gainEstimate.fallback);
        details.put("gainSampleCount", gainEstimate.sampleCount);
        details.put("gainSampleRatio", gainSampleRatio);
        details.put("gainSampleMinimum", HDR_MIN_RELIABLE_GAIN_SAMPLE_COUNT);
        details.put("blendStartHgDn", HDR_HG_BLEND_START);
        details.put("blendEndHgDn", HDR_HG_BLEND_END);
        details.put("hgSaturatedCount", hgSaturatedCount);
        details.put("hgSaturatedRatio", hgSaturatedRatio);
        details.put("lgSaturatedCount", lgSaturatedCount);
        details.put("lgSaturatedRatio", lgSaturatedRatio);
        details.put("hgBlackCount", hgBlackCount);
        details.put("hgBlackRatio", hgBlackRatio);
        details.put("lgBlackCount", lgBlackCount);
        details.put("lgBlackRatio", lgBlackRatio);
        Map<String, String> maskValues = new LinkedHashMap<>();
        maskValues.put("0", "HG_USED");
        maskValues.put("1", "LG_USED_AFTER_GAIN_SCALING");
        maskValues.put("2", "HG_LG_BLEND");
        maskValues.put("3", "HG_AND_LG_BOTH_SATURATED");
        details.put("maskValues", maskValues);
        details.put("hgPixelCount", hgPixelCount);
        details.put("hgUsedRatio", hgUsedRatio);
        details.put("lgPixelCount", lgPixelCount);
        details.put("lgUsedRatio", lgUsedRatio);
        details.put("blendPixelCount", blendPixelCount);
        details.put("blendRatio", blendRatio);
        details.put("bothSaturatedCount", bothSaturatedCount);
        details.put("bothSaturatedRatio", bothSaturatedRatio);
        details.put("lgCompensationPixelCount", lgPixelCount + blendPixelCount);
        details.put("lgCompensationRatio", lgCompensationRatio);
        details.put("warningBothSaturatedRatio", HDR_WARNING_BOTH_SATURATED_RATIO);
        details.put("failBothSaturatedRatio", HDR_FAIL_BOTH_SATURATED_RATIO);
        details.put("warningLgSaturatedRatio", HDR_WARNING_LG_SATURATED_RATIO);
        details.put("warningHgUsedRatioLow", HDR_WARNING_HG_USED_RATIO_LOW);
        details.put("fusedStoragePolicy", "RAW16_LOW12_COMPATIBLE_CLAMPED_TO_0_4095");
        details.put("fusedStorageNote",
                "当前主图仍进入既有RAW16_LOW12质量/处理/光谱管线；HG/LG平面已额外保存，后续可升级为扩展动态范围定量输出。");
        return new HdrFusionResult(fusedPixels16, fusionMask, gainRatio, details);
    }

    /**
     * HDR 暗场不是光谱场景，不能套用普通 HDR 的 HG/LG 强度融合规则。
     *
     * <p>这里生成一张“诊断合成主图”：逐像素取 HG/LG 两个暗场平面中的较大 DN，
     * 目的是让任一增益平面的暗电平偏高、亮坏点或固定图案噪声更容易在预览中暴露。
     * 真正用于后续 HDR 校准包的仍然是单独保存的 hg.raw16le.bin 和 lg.raw16le.bin。</p>
     */
    private HdrFusionResult buildHdrDarkDiagnosticComposite(int width,
                                                            int height,
                                                            short[] hgPixels16,
                                                            short[] lgPixels16) {
        short[] diagnosticPixels16 = new short[hgPixels16.length];
        byte[] diagnosticMask = new byte[hgPixels16.length];
        int hgDominantCount = 0;
        int lgDominantCount = 0;
        int equalCount = 0;
        long hgSum = 0L;
        long lgSum = 0L;
        long hgSquareSum = 0L;
        long lgSquareSum = 0L;
        int hgMax = 0;
        int lgMax = 0;
        int hgBrightCount = 0;
        int lgBrightCount = 0;
        final int brightReferenceDn = 512;

        for (int index = 0; index < hgPixels16.length; index++) {
            int hg = hgPixels16[index] & 0x0FFF;
            int lg = lgPixels16[index] & 0x0FFF;
            hgSum += hg;
            lgSum += lg;
            hgSquareSum += (long) hg * hg;
            lgSquareSum += (long) lg * lg;
            hgMax = Math.max(hgMax, hg);
            lgMax = Math.max(lgMax, lg);
            if (hg >= brightReferenceDn) {
                hgBrightCount++;
            }
            if (lg >= brightReferenceDn) {
                lgBrightCount++;
            }

            if (hg > lg) {
                diagnosticPixels16[index] = (short) hg;
                diagnosticMask[index] = 0;
                hgDominantCount++;
            } else if (lg > hg) {
                diagnosticPixels16[index] = (short) lg;
                diagnosticMask[index] = 1;
                lgDominantCount++;
            } else {
                diagnosticPixels16[index] = (short) hg;
                diagnosticMask[index] = 2;
                equalCount++;
            }
        }

        int totalPixelCount = hgPixels16.length;
        double hgMean = ratio(hgSum, totalPixelCount);
        double lgMean = ratio(lgSum, totalPixelCount);
        double hgVariance = Math.max(0.0d, ratio(hgSquareSum, totalPixelCount) - hgMean * hgMean);
        double lgVariance = Math.max(0.0d, ratio(lgSquareSum, totalPixelCount) - lgMean * lgMean);

        Map<String, String> maskValues = new LinkedHashMap<>();
        maskValues.put("0", "HG_DARK_DN_IS_LARGER");
        maskValues.put("1", "LG_DARK_DN_IS_LARGER");
        maskValues.put("2", "HG_LG_DARK_DN_EQUAL");

        List<String> diagnosticReasons = new ArrayList<>();
        diagnosticReasons.add("HDR暗场样本已按HG/LG双平面保存；主图仅为逐像素取较大暗电平的诊断合成图");
        diagnosticReasons.add("后续HDR校准包应分别使用HG暗场参考和LG暗场参考，而不是使用该诊断主图");

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("hdrVersion", "HDR_DARK_CAPTURE_V1");
        details.put("captureScene", "HDR_DARK");
        details.put("payloadLayout", "HG_FULL_FRAME_THEN_LG_FULL_FRAME");
        details.put("width", width);
        details.put("height", height);
        details.put("totalPixelCount", totalPixelCount);
        details.put("hdrDiagnosticStatus", "PASS");
        details.put("hdrDiagnosticReasons", diagnosticReasons);
        details.put("diagnosticCompositePolicy", "MAX_OF_HG_LG_DARK_DN");
        details.put("diagnosticCompositeMeaning",
                "仅用于预览和审计，逐像素显示HG/LG暗场中更高的DN，便于暴露任一平面的暗场异常");
        details.put("gainRatio", null);
        details.put("gainRatioMeaning", "HDR暗场采集不估计HG/LG光谱增益比");
        details.put("gainRatioFallback", false);
        details.put("gainSampleCount", 0);
        details.put("gainSampleRatio", 0.0d);
        details.put("maskValues", maskValues);
        details.put("hgDominantCount", hgDominantCount);
        details.put("hgDominantRatio", ratio(hgDominantCount, totalPixelCount));
        details.put("lgDominantCount", lgDominantCount);
        details.put("lgDominantRatio", ratio(lgDominantCount, totalPixelCount));
        details.put("equalCount", equalCount);
        details.put("equalRatio", ratio(equalCount, totalPixelCount));
        details.put("hgDarkMeanDn", hgMean);
        details.put("hgDarkStddevDn", Math.sqrt(hgVariance));
        details.put("hgDarkMaxDn", hgMax);
        details.put("hgBrightReferenceDn", brightReferenceDn);
        details.put("hgBrightReferenceCount", hgBrightCount);
        details.put("hgBrightReferenceRatio", ratio(hgBrightCount, totalPixelCount));
        details.put("lgDarkMeanDn", lgMean);
        details.put("lgDarkStddevDn", Math.sqrt(lgVariance));
        details.put("lgDarkMaxDn", lgMax);
        details.put("lgBrightReferenceDn", brightReferenceDn);
        details.put("lgBrightReferenceCount", lgBrightCount);
        details.put("lgBrightReferenceRatio", ratio(lgBrightCount, totalPixelCount));
        details.put("fusedStoragePolicy", "HDR_DARK_DIAGNOSTIC_COMPOSITE_RAW16_LOW12");
        details.put("fusedStorageNote",
                "该raw16le.bin不是普通HDR融合光谱主图，只是HDR暗场诊断合成图；真实HDR暗场校准应读取单独保存的HG/LG平面。");

        return new HdrFusionResult(diagnosticPixels16, diagnosticMask, null, details);
    }

    /**
     * HDR 平场同样不是一张普通光谱图；这里的主图只用于预览和审计。
     * 后续HDR平场校准会分别读取 hg.raw16le.bin 和 lg.raw16le.bin 构建 HG/LG 平场参考。
     */
    private HdrFusionResult buildHdrFlatDiagnosticComposite(int width,
                                                            int height,
                                                            short[] hgPixels16,
                                                            short[] lgPixels16) {
        short[] diagnosticPixels16 = new short[hgPixels16.length];
        byte[] diagnosticMask = new byte[hgPixels16.length];
        int hgDominantCount = 0;
        int lgDominantCount = 0;
        int equalCount = 0;
        long hgSum = 0L;
        long lgSum = 0L;
        long hgSquareSum = 0L;
        long lgSquareSum = 0L;
        int hgSaturatedCount = 0;
        int lgSaturatedCount = 0;
        int hgBlackCount = 0;
        int lgBlackCount = 0;

        for (int index = 0; index < hgPixels16.length; index++) {
            int hg = hgPixels16[index] & 0x0FFF;
            int lg = lgPixels16[index] & 0x0FFF;
            hgSum += hg;
            lgSum += lg;
            hgSquareSum += (long) hg * hg;
            lgSquareSum += (long) lg * lg;
            if (hg >= HDR_RAW12_MAX) {
                hgSaturatedCount++;
            }
            if (lg >= HDR_RAW12_MAX) {
                lgSaturatedCount++;
            }
            if (hg == 0) {
                hgBlackCount++;
            }
            if (lg == 0) {
                lgBlackCount++;
            }

            if (hg > lg) {
                diagnosticPixels16[index] = (short) hg;
                diagnosticMask[index] = 0;
                hgDominantCount++;
            } else if (lg > hg) {
                diagnosticPixels16[index] = (short) lg;
                diagnosticMask[index] = 1;
                lgDominantCount++;
            } else {
                diagnosticPixels16[index] = (short) hg;
                diagnosticMask[index] = 2;
                equalCount++;
            }
        }

        int totalPixelCount = hgPixels16.length;
        double hgMean = ratio(hgSum, totalPixelCount);
        double lgMean = ratio(lgSum, totalPixelCount);
        double hgVariance = Math.max(0.0d, ratio(hgSquareSum, totalPixelCount) - hgMean * hgMean);
        double lgVariance = Math.max(0.0d, ratio(lgSquareSum, totalPixelCount) - lgMean * lgMean);

        Map<String, String> maskValues = new LinkedHashMap<>();
        maskValues.put("0", "HG_FLAT_DN_IS_LARGER");
        maskValues.put("1", "LG_FLAT_DN_IS_LARGER");
        maskValues.put("2", "HG_LG_FLAT_DN_EQUAL");

        List<String> diagnosticReasons = new ArrayList<>();
        diagnosticReasons.add("HDR平场样本已按HG/LG双平面保存；主图仅为逐像素取较大DN的诊断合成图");
        diagnosticReasons.add("后续HDR校准包应分别使用HG平场参考和LG平场参考，而不是使用该诊断主图");

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("hdrVersion", "HDR_FLAT_CAPTURE_V1");
        details.put("captureScene", "HDR_FLAT");
        details.put("payloadLayout", "HG_FULL_FRAME_THEN_LG_FULL_FRAME");
        details.put("width", width);
        details.put("height", height);
        details.put("totalPixelCount", totalPixelCount);
        details.put("hdrDiagnosticStatus", "PASS");
        details.put("hdrDiagnosticReasons", diagnosticReasons);
        details.put("diagnosticCompositePolicy", "MAX_OF_HG_LG_FLAT_DN");
        details.put("diagnosticCompositeMeaning",
                "仅用于预览和审计，逐像素显示HG/LG平场中更高的DN，便于暴露任一平面的平场异常");
        details.put("gainRatio", null);
        details.put("gainRatioMeaning", "HDR平场采集不估计HG/LG光谱增益比");
        details.put("gainRatioFallback", false);
        details.put("gainSampleCount", 0);
        details.put("gainSampleRatio", 0.0d);
        details.put("maskValues", maskValues);
        details.put("hgDominantCount", hgDominantCount);
        details.put("hgDominantRatio", ratio(hgDominantCount, totalPixelCount));
        details.put("lgDominantCount", lgDominantCount);
        details.put("lgDominantRatio", ratio(lgDominantCount, totalPixelCount));
        details.put("equalCount", equalCount);
        details.put("equalRatio", ratio(equalCount, totalPixelCount));
        details.put("hgFlatMeanDn", hgMean);
        details.put("hgFlatStddevDn", Math.sqrt(hgVariance));
        details.put("hgSaturatedCount", hgSaturatedCount);
        details.put("hgSaturatedRatio", ratio(hgSaturatedCount, totalPixelCount));
        details.put("hgBlackCount", hgBlackCount);
        details.put("hgBlackRatio", ratio(hgBlackCount, totalPixelCount));
        details.put("lgFlatMeanDn", lgMean);
        details.put("lgFlatStddevDn", Math.sqrt(lgVariance));
        details.put("lgSaturatedCount", lgSaturatedCount);
        details.put("lgSaturatedRatio", ratio(lgSaturatedCount, totalPixelCount));
        details.put("lgBlackCount", lgBlackCount);
        details.put("lgBlackRatio", ratio(lgBlackCount, totalPixelCount));
        details.put("fusedStoragePolicy", "HDR_FLAT_DIAGNOSTIC_COMPOSITE_RAW16_LOW12");
        details.put("fusedStorageNote",
                "该raw16le.bin不是普通HDR融合光谱主图，只是HDR平场诊断合成图；真实HDR平场校准应读取单独保存的HG/LG平面。");

        return new HdrFusionResult(diagnosticPixels16, diagnosticMask, null, details);
    }

    private HdrGainEstimate estimateHdrGainRatio(short[] hgPixels16, short[] lgPixels16) {
        List<Double> ratios = new ArrayList<>();
        for (int index = 0; index < hgPixels16.length; index++) {
            int hg = hgPixels16[index] & 0x0FFF;
            int lg = lgPixels16[index] & 0x0FFF;
            if (hg >= HDR_MIN_GAIN_SAMPLE_HG
                    && hg <= HDR_MAX_GAIN_SAMPLE_HG
                    && lg >= HDR_MIN_GAIN_SAMPLE_LG
                    && lg <= HDR_MAX_GAIN_SAMPLE_LG) {
                ratios.add((double) hg / (double) lg);
            }
        }
        if (ratios.size() < HDR_MIN_RELIABLE_GAIN_SAMPLE_COUNT) {
            return new HdrGainEstimate(HDR_DEFAULT_GAIN_RATIO, ratios.size(), true);
        }
        Collections.sort(ratios);
        double median;
        int middle = ratios.size() / 2;
        if ((ratios.size() % 2) == 0) {
            median = (ratios.get(middle - 1) + ratios.get(middle)) / 2.0d;
        } else {
            median = ratios.get(middle);
        }
        if (!Double.isFinite(median)) {
            return new HdrGainEstimate(HDR_DEFAULT_GAIN_RATIO, ratios.size(), true);
        }
        double clamped = Math.max(HDR_MIN_GAIN_RATIO, Math.min(HDR_MAX_GAIN_RATIO, median));
        return new HdrGainEstimate(clamped, ratios.size(), clamped != median);
    }

    private double ratio(int numerator, int denominator) {
        return denominator <= 0 ? 0.0d : (double) numerator / (double) denominator;
    }

    private double ratio(long numerator, int denominator) {
        return denominator <= 0 ? 0.0d : (double) numerator / (double) denominator;
    }

    private String moreSevereHdrStatus(String left, String right) {
        return hdrStatusSeverity(right) > hdrStatusSeverity(left) ? right : left;
    }

    private int hdrStatusSeverity(String status) {
        if ("FAIL".equals(status)) {
            return 3;
        }
        if ("WARNING".equals(status)) {
            return 2;
        }
        if ("PASS".equals(status)) {
            return 1;
        }
        return 0;
    }

    private byte[] toLittleEndianRawBytes(short[] pixels16) {
        ByteBuffer buffer = ByteBuffer.allocate(pixels16.length * Short.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (short pixel : pixels16) {
            // JNI传来的short可能被Java视为有符号数；按位与后只保留0~4095。
            buffer.putShort((short) (pixel & 0x0FFF));
        }
        return buffer.array();
    }

    private byte[] readRaw16LeBytes(Path rawFile, int expectedPixels) throws IOException {
        byte[] bytes = Files.readAllBytes(rawFile);
        if (bytes.length != expectedPixels * Short.BYTES) {
            throw new IOException("RAW文件长度与图像尺寸不一致");
        }
        return bytes;
    }

    private short[] readRaw16Le(Path rawFile, int expectedPixels) throws IOException {
        byte[] bytes = readRaw16LeBytes(rawFile, expectedPixels);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        short[] pixels16 = new short[expectedPixels];
        for (int index = 0; index < expectedPixels; index++) {
            pixels16[index] = (short) (buffer.getShort() & 0x0FFF);
        }
        return pixels16;
    }

    private String toHexByte(int value) {
        return HEX_BYTE_LOOKUP[value & 0xFF];
    }

    private static String[] buildHexByteLookup() {
        String[] lookup = new String[256];
        char[] digits = "0123456789ABCDEF".toCharArray();
        for (int value = 0; value < lookup.length; value++) {
            lookup[value] = new String(new char[] {
                    digits[(value >>> 4) & 0x0F],
                    digits[value & 0x0F]
            });
        }
        return lookup;
    }

    private int clampPixelWindowStart(Integer requestedStart, int imageLength) {
        int start = requestedStart == null ? 0 : requestedStart;
        if (start < 0) {
            return 0;
        }
        if (start >= imageLength) {
            return imageLength - 1;
        }
        return start;
    }

    private int clampPixelWindowSize(Integer requestedSize, int availableLength) {
        int size = requestedSize == null ? DEFAULT_PIXEL_WINDOW_SIZE : requestedSize;
        if (size < 1) {
            size = 1;
        }
        if (size > MAX_PIXEL_WINDOW_SIZE) {
            size = MAX_PIXEL_WINDOW_SIZE;
        }
        return Math.min(size, availableLength);
    }

    private int clampPayloadWindowStart(Integer requestedStart, int payloadPixelCount) {
        int start = requestedStart == null ? 0 : requestedStart;
        if (start < 0) {
            return 0;
        }
        if (start >= payloadPixelCount) {
            return Math.max(0, payloadPixelCount - 1);
        }
        return start;
    }

    private int clampPayloadWindowCount(Integer requestedCount, int availableCount) {
        int count = requestedCount == null ? DEFAULT_FPGA_PAYLOAD_WINDOW_SIZE : requestedCount;
        if (count < 1) {
            count = 1;
        }
        if (count > MAX_FPGA_PAYLOAD_WINDOW_SIZE) {
            count = MAX_FPGA_PAYLOAD_WINDOW_SIZE;
        }
        return Math.min(count, Math.max(1, availableCount));
    }

    private boolean isGlux1605Hdr4LaneReadout(String readoutOrder) {
        return readoutOrder != null
                && readoutOrder.toUpperCase().contains("GLUX1605_HDR_4LANE");
    }

    private String payloadPlaneName(String captureScene, int planeIndex) {
        String scene = normalizeCaptureScene(captureScene);
        if (isHdrCaptureScene(scene)) {
            if (planeIndex == 0) {
                if ("HDR_DARK".equals(scene)) {
                    return "HG_DARK";
                }
                if ("HDR_FLAT".equals(scene)) {
                    return "HG_FLAT";
                }
                return "HG";
            }
            if (planeIndex == 1) {
                if ("HDR_DARK".equals(scene)) {
                    return "LG_DARK";
                }
                if ("HDR_FLAT".equals(scene)) {
                    return "LG_FLAT";
                }
                return "LG";
            }
            return "HDR_PLANE_" + planeIndex;
        }
        return "SINGLE";
    }

    private boolean isRowMajorReadout(String readoutOrder) {
        return readoutOrder != null && "ROW_MAJOR".equals(readoutOrder.trim().toUpperCase());
    }

    private String pixelSourceLabel(String normalizedSource, String readoutOrder) {
        boolean rowMajor = isRowMajorReadout(readoutOrder);
        if ("PROCESSED".equals(normalizedSource)) {
            return rowMajor ? "处理后 RAW16（正常行列）" : "处理后 RAW16（已重排）";
        }
        if ("CALIBRATED".equals(normalizedSource)) {
            return rowMajor ? "校准后 RAW16（正常行列）" : "校准后 RAW16（已重排）";
        }
        return rowMajor ? "原图 RAW16（正常行列）" : "重排后原图 RAW16";
    }

    private String pixelSourceDescription(String normalizedSource, String readoutOrder) {
        String label = pixelSourceLabel(normalizedSource, readoutOrder);
        if (isRowMajorReadout(readoutOrder)) {
            return label
                    + "；坐标已经是正常行列顺序。当前读出顺序为 ROW_MAJOR，表示 FPGA payload 本身已经按正常行列发送，"
                    + "native 只做 RAW16 低 12 位解析和文件保存，像素位置保持不变；该 RAW16 文件用于质量分析、图像处理和光谱提取。";
        }
        return label
                + "；坐标已经是正常行列顺序。FPGA 直接输出的跳跃/交织顺序保存在 fpga_payload.bin，"
                + "本 RAW16 文件已按 " + readoutOrder + " 重排后再用于质量分析、图像处理和光谱提取。";
    }

    private void writePreviewPng(int width, int height, byte[] pixels8, Path target) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        byte[] targetBuffer = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        System.arraycopy(pixels8, 0, targetBuffer, 0, targetBuffer.length);
        if (!ImageIO.write(image, "png", target.toFile())) {
            throw new IOException("当前JRE没有可用的PNG编码器");
        }
    }

    private byte[] toPreviewBytes(short[] pixels16) {
        byte[] pixels8 = new byte[pixels16.length];
        for (int index = 0; index < pixels16.length; index++) {
            int raw12 = pixels16[index] & 0x0FFF;
            pixels8[index] = (byte) ((raw12 * 255) / 4095);
        }
        return pixels8;
    }

    private Path buildFrameDirectory(String requestId) {
        LocalDate date = LocalDate.now();
        return getStorageRoot()
                .resolve(String.valueOf(date.getYear()))
                .resolve(String.format("%02d", date.getMonthValue()))
                .resolve(String.format("%02d", date.getDayOfMonth()))
                .resolve(requestId)
                .normalize();
    }

    private Path getStorageRoot() {
        return Paths.get(storageRoot).toAbsolutePath().normalize();
    }

    private String toStorageUri(Path file) {
        return getStorageRoot().relativize(file.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
    }

    private Path resolveStorageUri(String uri) {
        Path resolved = getStorageRoot().resolve(uri).normalize();
        if (!resolved.startsWith(getStorageRoot())) {
            throw new IllegalArgumentException("非法图像存储路径");
        }
        return resolved;
    }

    private String sha256Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                builder.append(String.format("%02x", value & 0xFF));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JRE不支持SHA-256", ex);
        }
    }

    private String encodePreviewDataUrl(Path previewFile) {
        try {
            byte[] bytes = Files.readAllBytes(previewFile);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (IOException ex) {
            return "";
        }
    }

    private ImageFrameResponse buildResponse(Long imageId,
                                             Long captureId,
                                             String requestId,
                                             String captureScene,
                                             OffsetDateTime timestamp,
                                             int width,
                                             int height,
                                             long payloadLength,
                                             Path previewFile,
                                             Path calibratedPreviewFile,
                                             String fpgaPayloadStorageUri,
                                             String fpgaPayloadSha256,
                                             String readoutOrder,
                                             Boolean integrityPassed,
                                             String integrityResultCode,
                                             QualityAnalysisResult quality,
                                             QualityDispositionResult disposition,
                                             SavedProcessingResult processing) {
        ImageFrameResponse response = new ImageFrameResponse();
        response.setId(imageId);
        response.setCaptureId(captureId);
        response.setRequestId(requestId);
        response.setCaptureScene(normalizeCaptureScene(captureScene));
        response.setTimestamp(timestamp);
        response.setWidth(width);
        response.setHeight(height);
        response.setRaw8Length((long) width * height);
        response.setRaw16Length((long) width * height);
        response.setPayloadLength(payloadLength);
        response.setPixelFormat(PIXEL_FORMAT);
        response.setFpgaPayloadStorageUri(fpgaPayloadStorageUri);
        response.setFpgaPayloadSha256(fpgaPayloadSha256);
        response.setReadoutOrder(readoutOrder);
        response.setImageDataUrl(encodePreviewDataUrl(previewFile));
        if (calibratedPreviewFile != null) {
            response.setCalibratedImageDataUrl(encodePreviewDataUrl(calibratedPreviewFile));
        }
        response.setIntegrityPassed(integrityPassed);
        response.setIntegrityResultCode(integrityResultCode);
        if (quality != null) {
            Map<String, Object> responseQualityDetails =
                    withCalibrationReferenceDeletionAudit(quality.getDetails());
            response.setQualityStatus(quality.getQualityStatus());
            response.setPixelMin(quality.getPixelMin());
            response.setPixelMax(quality.getPixelMax());
            response.setPixelMean(quality.getPixelMean());
            response.setPixelStddev(quality.getPixelStddev());
            response.setBlackPixelRatio(quality.getBlackPixelRatio());
            response.setSaturationPixelRatio(quality.getSaturationPixelRatio());
            response.setAbnormalRowCount(quality.getAbnormalRowCount());
            response.setAbnormalColumnCount(quality.getAbnormalColumnCount());
            response.setBadPixelCount(quality.getBadPixelCount());
            response.setQualitySummaryMessage(quality.getSummaryMessage());
            response.setQualityDetails(responseQualityDetails);
            Map<String, Object> originalQuality = qualityToMap(quality);
            originalQuality.put("details", new LinkedHashMap<>(responseQualityDetails));
            response.setOriginalQualitySnapshot(originalQuality);
            Map<String, Object> rawHardQuality = asNullableMap(responseQualityDetails.get("rawHardQuality"));
            response.setRawHardQualitySnapshot(rawHardQuality == null ? qualityToMap(quality) : rawHardQuality);
            response.setCalibratedQualitySnapshot(asNullableMap(responseQualityDetails.get("calibratedQuality")));
        }
        if (disposition != null) {
            response.setDispositionStatus(disposition.getDispositionStatus());
            response.setUsableForSpectral(disposition.isUsableForSpectral());
            response.setDispositionMessage(disposition.getSummaryMessage());
            response.setRecommendedActions(disposition.getRecommendedActions());
            response.setDispositionReasonCodes(disposition.getReasonCodes());
        }
        applyProcessingResponse(response, processing);
        return response;
    }

    @SuppressWarnings("unchecked")
    private void applyProcessingResponse(ImageFrameResponse response, SavedProcessingResult processing) {
        if (processing == null) {
            return;
        }
        response.setProcessingStatus(processing.processingStatus);
        response.setProcessingMessage(processing.processingMessage);
        if (processing.processedPreviewFile != null) {
            response.setProcessedImageDataUrl(encodePreviewDataUrl(processing.processedPreviewFile));
        }

        Map<String, Object> details = processing.processingDetails == null
                ? Collections.<String, Object>emptyMap()
                : processing.processingDetails;
        response.setExecutedProcessingActions(extractMapList(details.get("executedActions")));

        Object processedQualityValue = details.get("processedQuality");
        if (processedQualityValue instanceof Map) {
            Map<String, Object> processedQuality = (Map<String, Object>) processedQualityValue;
            response.setProcessedQualityStatus(asNullableString(processedQuality.get("qualityStatus")));
            response.setProcessedPixelMin(asNullableInteger(processedQuality.get("pixelMin")));
            response.setProcessedPixelMax(asNullableInteger(processedQuality.get("pixelMax")));
            response.setProcessedPixelMean(asNullableDouble(processedQuality.get("pixelMean")));
            response.setProcessedPixelStddev(asNullableDouble(processedQuality.get("pixelStddev")));
            response.setProcessedBlackPixelRatio(asNullableDouble(processedQuality.get("blackPixelRatio")));
            response.setProcessedSaturationPixelRatio(asNullableDouble(processedQuality.get("saturationPixelRatio")));
            response.setProcessedAbnormalRowCount(asNullableInteger(processedQuality.get("abnormalRowCount")));
            response.setProcessedAbnormalColumnCount(asNullableInteger(processedQuality.get("abnormalColumnCount")));
            response.setProcessedBadPixelCount(asNullableInteger(processedQuality.get("badPixelCount")));
            response.setProcessedQualitySummaryMessage(asNullableString(processedQuality.get("summaryMessage")));
            response.setProcessedQualityDetails(asNullableMap(processedQuality.get("details")));
            response.setProcessedQualitySnapshot(new LinkedHashMap<>(processedQuality));
        }

        Object processedDispositionValue = details.get("processedDisposition");
        if (processedDispositionValue instanceof Map) {
            Map<String, Object> processedDisposition = (Map<String, Object>) processedDispositionValue;
            response.setProcessedDispositionStatus(asNullableString(processedDisposition.get("dispositionStatus")));
            response.setProcessedUsableForSpectral(asNullableBoolean(processedDisposition.get("usableForSpectral")));
            response.setProcessedDispositionMessage(asNullableString(processedDisposition.get("summaryMessage")));
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractMapList(Object value) {
        if (!(value instanceof List)) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : (List<?>) value) {
            if (item instanceof Map) {
                result.add(new LinkedHashMap<>((Map<String, Object>) item));
            }
        }
        return result;
    }

    private String asNullableString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Boolean asNullableBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value == null) {
            return null;
        }
        return Boolean.valueOf(String.valueOf(value));
    }

    private Integer asNullableInteger(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Long asNullableLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Double asNullableDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asNullableMap(Object value) {
        if (value instanceof Map) {
            return new LinkedHashMap<>((Map<String, Object>) value);
        }
        return null;
    }

    /**
     * 给接口响应增加“历史校准包是否仍存在”的审计信息。
     *
     * <p>普通图像保存的是采集当时的校准快照，而不是强外键；这样历史包可以被用户删除。
     * 但打开旧图像时，研究人员需要知道当时用过的暗场/平场包现在是否还能追溯到完整文件。
     * 因此这里不改数据库里的原始质量记录，只在响应中临时补充 deleted 标志和提示文本。</p>
     */
    private Map<String, Object> withCalibrationReferenceDeletionAudit(Map<String, Object> details) {
        Map<String, Object> auditedDetails = details == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(details);
        Map<String, Object> calibration = asNullableMap(auditedDetails.get("calibration"));
        if (calibration == null) {
            return auditedDetails;
        }

        Long darkCalibrationId = asNullableLong(calibration.get("darkCalibrationId"));
        Long flatCalibrationId = asNullableLong(calibration.get("flatCalibrationId"));
        boolean darkDeleted = darkCalibrationId != null && !calibrationSessionExists(darkCalibrationId);
        boolean flatDeleted = flatCalibrationId != null && !calibrationSessionExists(flatCalibrationId);
        calibration.put("darkCalibrationDeleted", darkDeleted);
        calibration.put("flatCalibrationDeleted", flatDeleted);

        List<String> deletedReferences = new ArrayList<>();
        if (darkDeleted) {
            deletedReferences.add("暗场 " + calibrationReferenceText(calibration, "dark", "D", darkCalibrationId));
        }
        if (flatDeleted) {
            deletedReferences.add("平场 " + calibrationReferenceText(calibration, "flat", "F", flatCalibrationId));
        }
        if (!deletedReferences.isEmpty()) {
            calibration.put("deletedCalibrationReferences", deletedReferences);
            calibration.put("deletedCalibrationWarning",
                    "该图像采集时使用过的历史校准包已删除：" + String.join("、", deletedReferences)
                            + "。图像文件和采集快照仍可查看，但无法再从校准包模块追溯该包的参考图和缺陷地图文件。");
        }
        auditedDetails.put("calibration", calibration);
        return auditedDetails;
    }

    private boolean calibrationSessionExists(Long sessionId) {
        if (sessionId == null) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_calibration_session WHERE id=?",
                Integer.class,
                sessionId);
        return count != null && count > 0;
    }

    private String calibrationReferenceText(Map<String, Object> calibration,
                                            String prefix,
                                            String labelPrefix,
                                            Long fallbackId) {
        Object label = calibration.get(prefix + "CalibrationLabel");
        if (label instanceof String && !((String) label).trim().isEmpty()) {
            return (String) label;
        }
        Integer sessionNumber = asNullableInteger(calibration.get(prefix + "SessionNumber"));
        if (sessionNumber != null) {
            return labelPrefix + "-" + String.format("%03d", sessionNumber);
        }
        return "ID " + fallbackId;
    }

    private StoredImageRow mapStoredImageRow(ResultSet resultSet) throws SQLException {
        StoredImageRow row = new StoredImageRow();
        row.imageId = resultSet.getLong("image_id");
        row.captureId = resultSet.getLong("capture_id");
        row.requestId = resultSet.getString("request_id");
        row.captureScene = resultSet.getString("capture_scene");
        row.receivedAt = resultSet.getObject("received_at", OffsetDateTime.class);
        row.width = resultSet.getInt("width");
        row.height = resultSet.getInt("height");
        row.payloadLength = resultSet.getLong("payload_length");
        row.pixelFormat = resultSet.getString("pixel_format");
        row.fpgaPayloadStorageUri = resultSet.getString("fpga_payload_storage_uri");
        row.fpgaPayloadSha256 = resultSet.getString("fpga_payload_sha256");
        row.readoutOrder = resultSet.getString("readout_order");
        row.hgRawStorageUri = resultSet.getString("hg_raw_storage_uri");
        row.lgRawStorageUri = resultSet.getString("lg_raw_storage_uri");
        row.hgPreviewStorageUri = resultSet.getString("hg_preview_storage_uri");
        row.lgPreviewStorageUri = resultSet.getString("lg_preview_storage_uri");
        row.hdrFusionMaskStorageUri = resultSet.getString("hdr_fusion_mask_storage_uri");
        row.hdrGainRatio = getNullableDouble(resultSet, "hdr_gain_ratio");
        row.hdrFusionDetails = parseJsonMap(resultSet.getString("hdr_fusion_details_json"));
        row.previewStorageUri = resultSet.getString("preview_storage_uri");
        row.calibratedPreviewStorageUri = resultSet.getString("calibrated_preview_storage_uri");
        row.processedStorageUri = resultSet.getString("processed_storage_uri");
        row.integrityPassed = (Boolean) resultSet.getObject("passed");
        row.integrityResultCode = resultSet.getString("result_code");
        row.qualityStatus = resultSet.getString("quality_status");
        row.pixelMin = (Integer) resultSet.getObject("pixel_min");
        row.pixelMax = (Integer) resultSet.getObject("pixel_max");
        row.pixelMean = getNullableDouble(resultSet, "pixel_mean");
        row.pixelStddev = getNullableDouble(resultSet, "pixel_stddev");
        row.blackPixelRatio = getNullableDouble(resultSet, "black_pixel_ratio");
        row.saturationPixelRatio = getNullableDouble(resultSet, "saturation_pixel_ratio");
        row.abnormalRowCount = (Integer) resultSet.getObject("abnormal_row_count");
        row.abnormalColumnCount = (Integer) resultSet.getObject("abnormal_column_count");
        row.badPixelCount = (Integer) resultSet.getObject("bad_pixel_count");
        row.dispositionStatus = resultSet.getString("disposition_status");
        row.usableForSpectral = (Boolean) resultSet.getObject("usable_for_spectral");
        row.dispositionMessage = resultSet.getString("disposition_message");
        row.recommendedActionsPayload = parseJsonMap(resultSet.getString("recommended_actions"));
        row.qualityDetails = parseJsonMap(resultSet.getString("quality_details"));
        row.qualitySummaryMessage = buildQualitySummaryMessage(row.qualityStatus, row.qualityDetails);
        row.processingStatus = resultSet.getString("processing_status");
        row.processingMessage = resultSet.getString("processing_message");
        row.processingDetails = parseJsonMap(resultSet.getString("processing_details"));
        return row;
    }

    private Double getNullableDouble(ResultSet resultSet, String columnName) throws SQLException {
        Object value = resultSet.getObject(columnName);
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).doubleValue();
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return Double.valueOf(value.toString());
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException ex) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("parseError", ex.getMessage());
            return fallback;
        }
    }

    private String normalizeCaptureScene(String captureScene) {
        if (captureScene == null || captureScene.trim().isEmpty()) {
            return "NORMAL";
        }
        String scene = captureScene.trim().toUpperCase();
        if ("CALIBRATION_DARK".equals(scene)) {
            return "DARK";
        }
        if ("CALIBRATION_FLAT".equals(scene)) {
            return "FLAT";
        }
        if ("CALIBRATION_HDR_DARK".equals(scene) || "HDR_CALIBRATION_DARK".equals(scene)) {
            return "HDR_DARK";
        }
        if ("CALIBRATION_HDR_FLAT".equals(scene) || "HDR_CALIBRATION_FLAT".equals(scene)) {
            return "HDR_FLAT";
        }
        if ("NORMAL".equals(scene)
                || "DARK".equals(scene)
                || "FLAT".equals(scene)
                || "HDR".equals(scene)
                || "HDR_DARK".equals(scene)
                || "HDR_FLAT".equals(scene)) {
            return scene;
        }
        return "NORMAL";
    }

    private boolean isCalibrationCapture(String captureScene) {
        String scene = normalizeCaptureScene(captureScene);
        return "DARK".equals(scene)
                || "FLAT".equals(scene)
                || "HDR_DARK".equals(scene)
                || "HDR_FLAT".equals(scene);
    }

    private boolean isHdrCaptureScene(String captureScene) {
        String scene = normalizeCaptureScene(captureScene);
        return "HDR".equals(scene) || "HDR_DARK".equals(scene) || "HDR_FLAT".equals(scene);
    }

    private Map<String, Object> buildCalibrationCaptureSkipDetails(String captureScene) {
        String normalizedScene = normalizeCaptureScene(captureScene);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("captureScene", normalizedScene);
        details.put("calibrationPackageEnabled", false);
        details.put("calibrationApplied", false);
        details.put("defectMapEnabled", false);
        details.put("defectMapApplied", false);
        details.put("preprocessingApplied", false);
        details.put("skipReason", "HDR_DARK".equals(normalizedScene)
                ? "HDR暗场校准样本必须保持HG/LG原始平面，不套用当前校准包，也不执行图像处理；主图仅为诊断合成图"
                : "HDR_FLAT".equals(normalizedScene)
                ? "HDR平场校准样本必须保持HG/LG原始平面，不套用当前校准包，也不执行图像处理；主图仅为诊断合成图"
                : "暗场/平场校准样本必须保持原始RAW，不套用当前校准包，也不执行图像处理");
        return details;
    }

    @SuppressWarnings("unchecked")
    private String buildQualitySummaryMessage(String qualityStatus, Map<String, Object> qualityDetails) {
        if (qualityStatus == null) {
            return null;
        }

        Object reasonsValue = qualityDetails == null ? null : qualityDetails.get("decisionReasons");
        if (reasonsValue instanceof List) {
            List<Object> reasons = (List<Object>) reasonsValue;
            if (!reasons.isEmpty()) {
                StringBuilder builder = new StringBuilder("基础质量分析");
                builder.append(qualityStatus).append(": ");
                for (int index = 0; index < reasons.size(); index++) {
                    if (index > 0) {
                        builder.append("；");
                    }
                    builder.append(String.valueOf(reasons.get(index)));
                }
                return builder.toString();
            }
        }

        return "基础质量分析" + qualityStatus;
    }

    private ImageFrameResponse toResponse(StoredImageRow row) {
        QualityAnalysisResult quality = row.toQualityAnalysisResult();
        QualityDispositionResult disposition = row.toQualityDispositionResult();
        if (quality != null && row.needsDispositionRecompute()) {
            disposition = qualityDispositionService.decide(quality);
        }
        SavedProcessingResult processing = row.toSavedProcessingResult(
                row.processedStorageUri == null ? null : resolveStorageUri(row.processedStorageUri));
        ImageFrameResponse response = buildResponse(
                row.imageId,
                row.captureId,
                row.requestId,
                row.captureScene,
                row.receivedAt,
                row.width,
                row.height,
                row.payloadLength,
                resolveStorageUri(row.previewStorageUri),
                row.calibratedPreviewStorageUri == null ? null : resolveStorageUri(row.calibratedPreviewStorageUri),
                row.fpgaPayloadStorageUri,
                row.fpgaPayloadSha256,
                row.readoutOrder,
                row.integrityPassed,
                row.integrityResultCode,
                quality,
                disposition,
                processing);
        response.setHgRawStorageUri(row.hgRawStorageUri);
        response.setLgRawStorageUri(row.lgRawStorageUri);
        response.setHgPreviewStorageUri(row.hgPreviewStorageUri);
        response.setLgPreviewStorageUri(row.lgPreviewStorageUri);
        response.setHdrFusionMaskStorageUri(row.hdrFusionMaskStorageUri);
        response.setHdrGainRatio(row.hdrGainRatio);
        response.setHdrFusionDetails(row.hdrFusionDetails == null
                ? Collections.emptyMap()
                : new LinkedHashMap<>(row.hdrFusionDetails));
        if (row.hgPreviewStorageUri != null && !row.hgPreviewStorageUri.trim().isEmpty()) {
            response.setHgImageDataUrl(encodePreviewDataUrl(resolveStorageUri(row.hgPreviewStorageUri)));
        }
        if (row.lgPreviewStorageUri != null && !row.lgPreviewStorageUri.trim().isEmpty()) {
            response.setLgImageDataUrl(encodePreviewDataUrl(resolveStorageUri(row.lgPreviewStorageUri)));
        }
        return response;
    }

    private String firstNonNullUri(ResultSet resultSet) throws SQLException {
        String raw = resultSet.getString("raw_storage_uri");
        if (raw != null) {
            return raw;
        }
        String preview = resultSet.getString("preview_storage_uri");
        return preview != null ? preview : resultSet.getString("processed_storage_uri");
    }

    private void deleteFrameDirectoryByUri(String uri) {
        if (uri == null || uri.trim().isEmpty()) {
            return;
        }
        Path file = resolveStorageUri(uri);
        deleteDirectoryQuietly(file.getParent());
    }

    private void deleteDirectoryQuietly(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted((left, right) -> right.compareTo(left))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // 数据库删除已经完成时，文件清理失败不应回滚业务事务。
                        }
                    });
        } catch (IOException ignored) {
            // 清理属于补偿动作，失败时由运维定期清理孤儿目录。
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("无法序列化JSON字段", ex);
        }
    }

    private static final class SavedProcessingResult {
        private final String processingStatus;
        private final String processingMessage;
        private final Map<String, Object> processingDetails;
        private final Path processedPreviewFile;

        private SavedProcessingResult(ProcessingResult result,
                                      Path processedPreviewFile,
                                      Map<String, Object> processingDetails) {
            this(
                    result.getProcessingStatus(),
                    result.getSummaryMessage(),
                    processingDetails,
                    processedPreviewFile);
        }

        private SavedProcessingResult(String processingStatus,
                                      String processingMessage,
                                      Map<String, Object> processingDetails) {
            this(processingStatus, processingMessage, processingDetails, null);
        }

        private SavedProcessingResult(String processingStatus,
                                      String processingMessage,
                                      Map<String, Object> processingDetails,
                                      Path processedPreviewFile) {
            this.processingStatus = processingStatus;
            this.processingMessage = processingMessage;
            this.processingDetails = processingDetails == null
                    ? Collections.emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(processingDetails));
            this.processedPreviewFile = processedPreviewFile;
        }
    }

    private static final class SavedCalibrationVersion {
        @SuppressWarnings("unused")
        private final Path calibratedRawFile;
        private final Path calibratedPreviewFile;

        private SavedCalibrationVersion(Path calibratedRawFile, Path calibratedPreviewFile) {
            this.calibratedRawFile = calibratedRawFile;
            this.calibratedPreviewFile = calibratedPreviewFile;
        }
    }

    private static final class ProcessSourceRow {
        private Long imageId;
        private Long captureId;
        private int width;
        private int height;
        private String rawStorageUri;
        private String calibratedRawStorageUri;
        private String processedStorageUri;
        private Map<String, Object> qualityDetails = Collections.emptyMap();
    }

    private static final class PixelSourceRow {
        private Long imageId;
        private int width;
        private int height;
        private String pixelFormat;
        private String readoutOrder;
        private String rawStorageUri;
        private String calibratedRawStorageUri;
        private Map<String, Object> processingDetails = Collections.emptyMap();
    }

    private static final class FpgaPayloadSourceRow {
        private Long imageId;
        private String captureScene;
        private int width;
        private int height;
        private String pixelFormat;
        private long payloadLength;
        private String payloadStorageUri;
        private String payloadSha256;
        private String readoutOrder;
    }

    private static final class PreprocessingResult {
        private final short[] pixels16;
        private final Map<String, Object> calibrationDetails;

        private PreprocessingResult(short[] pixels16, Map<String, Object> calibrationDetails) {
            this.pixels16 = pixels16;
            this.calibrationDetails = Collections.unmodifiableMap(new LinkedHashMap<>(calibrationDetails));
        }
    }

    private static final class HdrFusionResult {
        private final short[] fusedPixels16;
        private final byte[] fusionMask;
        private final Double gainRatio;
        private final Map<String, Object> details;

        private HdrFusionResult(short[] fusedPixels16,
                                byte[] fusionMask,
                                Double gainRatio,
                                Map<String, Object> details) {
            this.fusedPixels16 = fusedPixels16;
            this.fusionMask = fusionMask;
            this.gainRatio = gainRatio;
            this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
        }
    }

    private static final class HdrGainEstimate {
        private final double gainRatio;
        private final int sampleCount;
        private final boolean fallback;

        private HdrGainEstimate(double gainRatio, int sampleCount, boolean fallback) {
            this.gainRatio = gainRatio;
            this.sampleCount = sampleCount;
            this.fallback = fallback;
        }
    }

    private static final class StoredImageRow {
        private Long imageId;
        private Long captureId;
        private String requestId;
        private String captureScene;
        private OffsetDateTime receivedAt;
        private int width;
        private int height;
        private long payloadLength;
        private String pixelFormat;
        private String fpgaPayloadStorageUri;
        private String fpgaPayloadSha256;
        private String readoutOrder;
        private String hgRawStorageUri;
        private String lgRawStorageUri;
        private String hgPreviewStorageUri;
        private String lgPreviewStorageUri;
        private String hdrFusionMaskStorageUri;
        private Double hdrGainRatio;
        private Map<String, Object> hdrFusionDetails = Collections.emptyMap();
        private String previewStorageUri;
        private String calibratedPreviewStorageUri;
        private String processedStorageUri;
        private Boolean integrityPassed;
        private String integrityResultCode;
        private String qualityStatus;
        private Integer pixelMin;
        private Integer pixelMax;
        private Double pixelMean;
        private Double pixelStddev;
        private Double blackPixelRatio;
        private Double saturationPixelRatio;
        private Integer abnormalRowCount;
        private Integer abnormalColumnCount;
        private Integer badPixelCount;
        private String qualitySummaryMessage;
        private Map<String, Object> qualityDetails = Collections.emptyMap();
        private String dispositionStatus;
        private Boolean usableForSpectral;
        private String dispositionMessage;
        private Map<String, Object> recommendedActionsPayload = Collections.emptyMap();
        private String processingStatus;
        private String processingMessage;
        private Map<String, Object> processingDetails = Collections.emptyMap();

        private QualityAnalysisResult toQualityAnalysisResult() {
            if (qualityStatus == null) {
                return null;
            }
            return new QualityAnalysisResult(
                    qualityStatus,
                    SpectralImageQualityAnalysisService.ANALYSIS_VERSION,
                    pixelMin == null ? 0 : pixelMin,
                    pixelMax == null ? 0 : pixelMax,
                    pixelMean == null ? 0.0d : pixelMean,
                    pixelStddev == null ? 0.0d : pixelStddev,
                    blackPixelRatio == null ? 0.0d : blackPixelRatio,
                    saturationPixelRatio == null ? 0.0d : saturationPixelRatio,
                    abnormalRowCount == null ? 0 : abnormalRowCount,
                    abnormalColumnCount == null ? 0 : abnormalColumnCount,
                    badPixelCount == null ? 0 : badPixelCount,
                    qualityDetails == null ? Collections.emptyMap() : qualityDetails,
                    qualitySummaryMessage);
        }

        private boolean needsDispositionRecompute() {
            return dispositionStatus == null
                    || dispositionMessage == null
                    || extractActionMaps(recommendedActionsPayload).isEmpty();
        }

        private QualityDispositionResult toQualityDispositionResult() {
            if (dispositionStatus == null) {
                return null;
            }
            Map<String, Object> payload = recommendedActionsPayload == null
                    ? Collections.<String, Object>emptyMap()
                    : recommendedActionsPayload;
            return new QualityDispositionResult(
                    dispositionStatus,
                    Boolean.TRUE.equals(usableForSpectral),
                    dispositionMessage,
                    extractActionMaps(payload),
                    extractStringList(payload.get("reasonCodes")),
                    payload);
        }

        private SavedProcessingResult toSavedProcessingResult(Path processedPreviewFile) {
            if (processingStatus == null && processedPreviewFile == null) {
                return null;
            }
            return new SavedProcessingResult(
                    processingStatus == null ? "PROCESSED" : processingStatus,
                    processingMessage,
                    processingDetails == null ? Collections.emptyMap() : processingDetails,
                    processedPreviewFile);
        }

        @SuppressWarnings("unchecked")
        private static List<Map<String, Object>> extractActionMaps(Map<String, Object> payload) {
            if (payload == null) {
                return Collections.emptyList();
            }
            Object actionsValue = payload.get("actions");
            if (!(actionsValue instanceof List)) {
                return Collections.emptyList();
            }
            List<Map<String, Object>> actions = new ArrayList<>();
            for (Object item : (List<?>) actionsValue) {
                if (item instanceof Map) {
                    actions.add(new LinkedHashMap<>((Map<String, Object>) item));
                }
            }
            return actions;
        }

        private static List<String> extractStringList(Object value) {
            if (!(value instanceof List)) {
                return Collections.emptyList();
            }
            List<String> result = new ArrayList<>();
            for (Object item : (List<?>) value) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }
    }
}
