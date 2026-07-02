package springbootjni.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import springbootjni.dto.jni.ImageFrameResponse;
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
    private static final int MAX_HISTORY_SIZE = 50;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SpectralImageQualityAnalysisService qualityAnalysisService;
    private final SpectralImageQualityDispositionService qualityDispositionService;
    private final SpectralImageProcessingService imageProcessingService;

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
    public long createCapture(Long userId, String requestId, Map<String, Object> configSnapshot) {
        String configJson = toJson(configSnapshot);
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO t_spectral_capture " +
                        "(request_id, user_id, capture_scene, capture_status, trigger_time, config_snapshot) " +
                        "VALUES (?, ?, 'NORMAL', 'WAITING', CURRENT_TIMESTAMP, CAST(? AS jsonb)) RETURNING id",
                Long.class,
                requestId,
                userId,
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
                                                  String requestId,
                                                  int width,
                                                  int height,
                                                  short[] pixels16,
                                                  byte[] pixels8,
                                                  boolean verifyCrc,
                                                  boolean autoProcess,
                                                  long elapsedMs) {
        validateFrameBuffers(width, height, pixels16, pixels8);

        Path frameDirectory = buildFrameDirectory(requestId);
        try {
            Files.createDirectories(frameDirectory);

            byte[] rawBytes = toLittleEndianRawBytes(pixels16);
            Path rawFile = frameDirectory.resolve("raw16le.bin");
            Path previewFile = frameDirectory.resolve("preview.png");
            Files.write(rawFile, rawBytes);
            writePreviewPng(width, height, pixels8, previewFile);

            String rawRelativeUri = toStorageUri(rawFile);
            String previewRelativeUri = toStorageUri(previewFile);
            String rawSha256 = sha256Hex(rawBytes);

            Long imageId = jdbcTemplate.queryForObject(
                    "INSERT INTO t_spectral_image " +
                            "(capture_id, width, height, pixel_format, payload_length, raw_storage_uri, " +
                            "preview_storage_uri, raw_sha256, received_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP) RETURNING id",
                    Long.class,
                    captureId,
                    width,
                    height,
                    PIXEL_FORMAT,
                    rawBytes.length,
                    rawRelativeUri,
                    previewRelativeUri,
                    rawSha256);
            if (imageId == null) {
                throw new IllegalStateException("保存图像元数据后未返回主键");
            }

            Map<String, Object> details = new LinkedHashMap<>();
            details.put("verifyCrc", verifyCrc);
            details.put("autoProcess", autoProcess);
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
                    rawBytes.length,
                    rawBytes.length,
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
            QualityAnalysisResult quality = qualityAnalysisService.analyze(width, height, pixels16);
            QualityDispositionResult disposition = qualityDispositionService.decide(quality);
            saveQualityAnalysis(imageId, quality, disposition);
            SavedProcessingResult processing = null;
            if (autoProcess) {
                processing = tryProcessAndSaveImage(
                        captureId,
                        imageId,
                        width,
                        height,
                        frameDirectory,
                        pixels16,
                        quality,
                        disposition);
                if (processing == null) {
                    saveSkippedProcessingLog(captureId, imageId, disposition);
                    processing = new SavedProcessingResult(
                            "SKIPPED",
                            "自动处理已跳过：当前图片没有可自动修复动作，或无需修复。",
                            skippedProcessingDetails(disposition));
                }
            }

            jdbcTemplate.update(
                    "UPDATE t_spectral_capture SET capture_status=?, completed_at=CURRENT_TIMESTAMP, " +
                            "error_message=? WHERE id=?",
                    quality.toCaptureStatus(),
                    "PASS".equals(quality.getQualityStatus()) ? null : disposition.getSummaryMessage(),
                    captureId);

            return buildResponse(
                    imageId,
                    captureId,
                    requestId,
                    OffsetDateTime.now(ZoneOffset.UTC),
                    width,
                    height,
                    rawBytes.length,
                    previewFile,
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
                                                         QualityDispositionResult disposition) {
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
            Map<String, Object> details = buildProcessingDetails(result, processedRawUri, processedPreviewUri);

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

    private Map<String, Object> buildProcessingDetails(ProcessingResult result,
                                                       String processedRawUri,
                                                       String processedPreviewUri) {
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
        map.put("details", quality.getDetails());
        return map;
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
        List<StoredImageRow> rows = jdbcTemplate.query(
                "SELECT i.id AS image_id, c.id AS capture_id, c.request_id, i.received_at, " +
                        "i.width, i.height, i.payload_length, i.pixel_format, i.preview_storage_uri, " +
                        "i.processed_storage_uri, " +
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
                        "WHERE c.user_id=? ORDER BY i.received_at DESC LIMIT ?",
                (resultSet, rowNum) -> mapStoredImageRow(resultSet),
                userId,
                MAX_HISTORY_SIZE);

        List<ImageFrameResponse> responses = new ArrayList<>();
        for (StoredImageRow row : rows) {
            responses.add(toResponse(row));
        }
        return responses;
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
                        "i.processed_storage_uri " +
                        "FROM t_spectral_image i JOIN t_spectral_capture c ON c.id=i.capture_id " +
                        "WHERE i.id=? AND c.user_id=?",
                (resultSet, rowNum) -> {
                    ProcessSourceRow row = new ProcessSourceRow();
                    row.imageId = resultSet.getLong("image_id");
                    row.captureId = resultSet.getLong("capture_id");
                    row.width = resultSet.getInt("width");
                    row.height = resultSet.getInt("height");
                    row.rawStorageUri = resultSet.getString("raw_storage_uri");
                    row.processedStorageUri = resultSet.getString("processed_storage_uri");
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
            short[] pixels16 = readRaw16Le(rawFile, source.width * source.height);
            QualityAnalysisResult quality = qualityAnalysisService.analyze(source.width, source.height, pixels16);
            QualityDispositionResult disposition = qualityDispositionService.decide(quality);
            saveQualityAnalysis(source.imageId, quality, disposition);
            SavedProcessingResult processing = tryProcessAndSaveImage(
                    source.captureId,
                    source.imageId,
                    source.width,
                    source.height,
                    rawFile.getParent(),
                    pixels16,
                    quality,
                    disposition);
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
        throw new IllegalStateException("图像处理后未能重新加载记录");
    }

    /**
     * 删除当前用户的一张历史图片。删除采集主记录会通过外键级联删除图像和完整性记录。
     */
    @Transactional
    public boolean deleteImage(Long userId, long imageId) {
        List<String> uris = jdbcTemplate.query(
                "SELECT i.raw_storage_uri, i.preview_storage_uri, i.processed_storage_uri " +
                        "FROM t_spectral_image i JOIN t_spectral_capture c ON c.id=i.capture_id " +
                        "WHERE i.id=? AND c.user_id=?",
                (resultSet, rowNum) -> firstNonNullUri(resultSet),
                imageId,
                userId);
        if (uris.isEmpty()) {
            return false;
        }

        int affected = jdbcTemplate.update(
                "DELETE FROM t_spectral_capture c USING t_spectral_image i " +
                        "WHERE i.capture_id=c.id AND i.id=? AND c.user_id=?",
                imageId,
                userId);
        if (affected > 0) {
            deleteFrameDirectoryByUri(uris.get(0));
        }
        return affected > 0;
    }

    /**
     * 清空当前用户的全部采集历史，不影响其他用户。
     */
    @Transactional
    public int clearImages(Long userId) {
        List<String> rawUris = jdbcTemplate.query(
                "SELECT i.raw_storage_uri FROM t_spectral_image i " +
                        "JOIN t_spectral_capture c ON c.id=i.capture_id WHERE c.user_id=?",
                (resultSet, rowNum) -> resultSet.getString("raw_storage_uri"),
                userId);

        int affected = jdbcTemplate.update("DELETE FROM t_spectral_capture WHERE user_id=?", userId);
        for (String uri : rawUris) {
            deleteFrameDirectoryByUri(uri);
        }
        return affected;
    }

    private void validateFrameBuffers(int width, int height, short[] pixels16, byte[] pixels8) {
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

    private short[] readRaw16Le(Path rawFile, int expectedPixels) throws IOException {
        byte[] bytes = Files.readAllBytes(rawFile);
        if (bytes.length != expectedPixels * Short.BYTES) {
            throw new IOException("RAW文件长度与图像尺寸不一致");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        short[] pixels16 = new short[expectedPixels];
        for (int index = 0; index < expectedPixels; index++) {
            pixels16[index] = (short) (buffer.getShort() & 0x0FFF);
        }
        return pixels16;
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
                                             OffsetDateTime timestamp,
                                             int width,
                                             int height,
                                             long payloadLength,
                                              Path previewFile,
                                              Boolean integrityPassed,
                                              String integrityResultCode,
                                              QualityAnalysisResult quality,
                                              QualityDispositionResult disposition,
                                              SavedProcessingResult processing) {
        ImageFrameResponse response = new ImageFrameResponse();
        response.setId(imageId);
        response.setCaptureId(captureId);
        response.setRequestId(requestId);
        response.setTimestamp(timestamp);
        response.setWidth(width);
        response.setHeight(height);
        response.setRaw8Length((long) width * height);
        response.setRaw16Length((long) width * height);
        response.setPayloadLength(payloadLength);
        response.setPixelFormat(PIXEL_FORMAT);
        response.setImageDataUrl(encodePreviewDataUrl(previewFile));
        response.setIntegrityPassed(integrityPassed);
        response.setIntegrityResultCode(integrityResultCode);
        if (quality != null) {
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
            response.setQualityDetails(quality.getDetails());
            response.setOriginalQualitySnapshot(qualityToMap(quality));
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

    private StoredImageRow mapStoredImageRow(ResultSet resultSet) throws SQLException {
        StoredImageRow row = new StoredImageRow();
        row.imageId = resultSet.getLong("image_id");
        row.captureId = resultSet.getLong("capture_id");
        row.requestId = resultSet.getString("request_id");
        row.receivedAt = resultSet.getObject("received_at", OffsetDateTime.class);
        row.width = resultSet.getInt("width");
        row.height = resultSet.getInt("height");
        row.payloadLength = resultSet.getLong("payload_length");
        row.pixelFormat = resultSet.getString("pixel_format");
        row.previewStorageUri = resultSet.getString("preview_storage_uri");
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
        return buildResponse(
                row.imageId,
                row.captureId,
                row.requestId,
                row.receivedAt,
                row.width,
                row.height,
                row.payloadLength,
                resolveStorageUri(row.previewStorageUri),
                row.integrityPassed,
                row.integrityResultCode,
                quality,
                disposition,
                processing);
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

    private static final class ProcessSourceRow {
        private Long imageId;
        private Long captureId;
        private int width;
        private int height;
        private String rawStorageUri;
        private String processedStorageUri;
    }

    private static final class StoredImageRow {
        private Long imageId;
        private Long captureId;
        private String requestId;
        private OffsetDateTime receivedAt;
        private int width;
        private int height;
        private long payloadLength;
        private String pixelFormat;
        private String previewStorageUri;
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
