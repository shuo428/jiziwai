package springbootjni.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import springbootjni.dto.jni.ImageFrameResponse;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
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

    @Value("${spectral.storage.root:D:/GraduationProject/spectral-images}")
    private String storageRoot;

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

            jdbcTemplate.update(
                    "UPDATE t_spectral_capture SET capture_status='PASS', completed_at=CURRENT_TIMESTAMP, " +
                            "error_message=NULL WHERE id=?",
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
                    "OK");
        } catch (RuntimeException | IOException ex) {
            deleteDirectoryQuietly(frameDirectory);
            throw new IllegalStateException("保存光谱图像失败: " + ex.getMessage(), ex);
        }
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
                        "ia.passed, ia.result_code " +
                        "FROM t_spectral_capture c " +
                        "JOIN t_spectral_image i ON i.capture_id=c.id " +
                        "LEFT JOIN t_image_integrity_analysis ia ON ia.capture_id=c.id " +
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
                                             String integrityResultCode) {
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
        return row;
    }

    private ImageFrameResponse toResponse(StoredImageRow row) {
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
                row.integrityResultCode);
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
    }
}
