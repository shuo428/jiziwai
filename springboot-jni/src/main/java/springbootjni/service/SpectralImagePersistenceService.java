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
                    disposition);
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
                        "ia.passed, ia.result_code, qa.quality_status, qa.pixel_min, qa.pixel_max, " +
                        "qa.pixel_mean, qa.pixel_stddev, qa.black_pixel_ratio, qa.saturation_pixel_ratio, " +
                        "qa.abnormal_row_count, qa.abnormal_column_count, qa.bad_pixel_count, " +
                        "qa.disposition_status, qa.usable_for_spectral, qa.disposition_message, " +
                        "qa.recommended_actions, qa.details AS quality_details " +
                        "FROM t_spectral_capture c " +
                        "JOIN t_spectral_image i ON i.capture_id=c.id " +
                        "LEFT JOIN t_image_integrity_analysis ia ON ia.capture_id=c.id " +
                        "LEFT JOIN t_image_quality_analysis qa ON qa.image_id=i.id " +
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

    private void writePreviewPng(int width, int height, byte[] pixels8, Path target) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        byte[] targetBuffer = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        System.arraycopy(pixels8, 0, targetBuffer, 0, targetBuffer.length);
        if (!ImageIO.write(image, "png", target.toFile())) {
            throw new IOException("当前JRE没有可用的PNG编码器");
        }
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
                                              QualityDispositionResult disposition) {
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
        }
        if (disposition != null) {
            response.setDispositionStatus(disposition.getDispositionStatus());
            response.setUsableForSpectral(disposition.isUsableForSpectral());
            response.setDispositionMessage(disposition.getSummaryMessage());
            response.setRecommendedActions(disposition.getRecommendedActions());
            response.setDispositionReasonCodes(disposition.getReasonCodes());
        }
        return response;
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
                disposition);
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
