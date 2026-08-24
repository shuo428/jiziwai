package springbootjni.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import springbootjni.dto.jni.CalibrationGlobalSettingsRequest;
import springbootjni.dto.jni.CalibrationGlobalSettingsResponse;
import springbootjni.dto.jni.CalibrationPreviewResponse;
import springbootjni.dto.jni.CalibrationRequest;
import springbootjni.dto.jni.CalibrationSessionResponse;

import javax.annotation.PostConstruct;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** 暗场、平场模拟数据和校准会话持久化服务。 */
@Service
@RequiredArgsConstructor
public class SpectralCalibrationService {
    private static final int SENSOR_MAX_DN = 4095;
    private static final int DEFAULT_WIDTH = 800;
    private static final int DEFAULT_HEIGHT = 600;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SpectralMultiFrameQualityAnalysisService multiFrameService;
    private final SpectralCalibrationQualityAnalysisService calibrationQualityAnalysisService;
    private final SpectralImageProcessingService imageProcessingService;

    @Value("${spectral.storage.root:D:/GraduationProject/spectral-images}")
    private String storageRoot;

    @PostConstruct
    public void ensureCalibrationTables() {
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS t_calibration_session (" +
                        "id BIGSERIAL PRIMARY KEY," +
                        "user_id BIGINT REFERENCES t_user(id) ON DELETE SET NULL," +
                        "session_number INTEGER NOT NULL," +
                        "calibration_type VARCHAR(16) NOT NULL CHECK (calibration_type IN ('DARK','FLAT','HDR_DARK','HDR_FLAT'))," +
                        "acquisition_mode VARCHAR(16) NOT NULL CHECK (acquisition_mode IN ('SIMULATED','HARDWARE','IMAGES'))," +
                        "status VARCHAR(16) NOT NULL CHECK (status IN ('PROCESSING','READY','FAILED'))," +
                        "expected_frame_count INTEGER NOT NULL," +
                        "frame_count INTEGER NOT NULL DEFAULT 0," +
                        "width INTEGER NOT NULL," +
                        "height INTEGER NOT NULL," +
                        "storage_uri TEXT," +
                        "defect_map_uri TEXT," +
                        "bad_pixel_count INTEGER NOT NULL DEFAULT 0," +
                        "bad_row_count INTEGER NOT NULL DEFAULT 0," +
                        "bad_column_count INTEGER NOT NULL DEFAULT 0," +
                        "summary JSONB NOT NULL DEFAULT '{}'::JSONB," +
                        "message TEXT," +
                        "created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                        "completed_at TIMESTAMPTZ" +
                        ")");
        jdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_calibration_session_type " +
                        "ON t_calibration_session(user_id, calibration_type, created_at DESC)");
        jdbcTemplate.execute(
                "ALTER TABLE IF EXISTS t_calibration_session " +
                        "DROP CONSTRAINT IF EXISTS t_calibration_session_calibration_type_check");
        jdbcTemplate.execute(
                "ALTER TABLE IF EXISTS t_calibration_session " +
                        "ALTER COLUMN calibration_type TYPE VARCHAR(16)");
        jdbcTemplate.execute(
                "ALTER TABLE IF EXISTS t_calibration_session " +
                        "ADD CONSTRAINT t_calibration_session_calibration_type_check " +
                        "CHECK (calibration_type IN ('DARK','FLAT','HDR_DARK','HDR_FLAT'))");
        jdbcTemplate.execute("ALTER TABLE t_calibration_session ADD COLUMN IF NOT EXISTS session_number INTEGER");
        jdbcTemplate.execute(
                "WITH ranked AS (" +
                        "SELECT id, ROW_NUMBER() OVER (PARTITION BY user_id, calibration_type ORDER BY created_at, id) AS no " +
                        "FROM t_calibration_session WHERE session_number IS NULL" +
                        ") UPDATE t_calibration_session s SET session_number=ranked.no FROM ranked WHERE s.id=ranked.id");
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_calibration_session_type_number " +
                "ON t_calibration_session(user_id, calibration_type, session_number)");
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS t_calibration_global_setting (" +
                        "user_id BIGINT PRIMARY KEY REFERENCES t_user(id) ON DELETE CASCADE," +
                        "enabled BOOLEAN NOT NULL DEFAULT FALSE," +
                        "dark_calibration_id BIGINT REFERENCES t_calibration_session(id) ON DELETE SET NULL," +
                        "flat_calibration_id BIGINT REFERENCES t_calibration_session(id) ON DELETE SET NULL," +
                        "defect_map_enabled BOOLEAN NOT NULL DEFAULT FALSE," +
                        "hdr_enabled BOOLEAN NOT NULL DEFAULT FALSE," +
                        "hdr_dark_calibration_id BIGINT REFERENCES t_calibration_session(id) ON DELETE SET NULL," +
                        "hdr_flat_calibration_id BIGINT REFERENCES t_calibration_session(id) ON DELETE SET NULL," +
                        "hdr_defect_map_enabled BOOLEAN NOT NULL DEFAULT FALSE," +
                        "updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP" +
                        ")");
        // 兼容已经由前一版创建的设置表：CREATE TABLE IF NOT EXISTS 不会为旧表补列。
        jdbcTemplate.execute("ALTER TABLE t_calibration_global_setting " +
                "ADD COLUMN IF NOT EXISTS dark_calibration_id BIGINT REFERENCES t_calibration_session(id) ON DELETE SET NULL");
        jdbcTemplate.execute("ALTER TABLE t_calibration_global_setting " +
                "ADD COLUMN IF NOT EXISTS flat_calibration_id BIGINT REFERENCES t_calibration_session(id) ON DELETE SET NULL");
        jdbcTemplate.execute("ALTER TABLE t_calibration_global_setting " +
                "ADD COLUMN IF NOT EXISTS defect_map_enabled BOOLEAN NOT NULL DEFAULT FALSE");
        jdbcTemplate.execute("ALTER TABLE t_calibration_global_setting " +
                "ADD COLUMN IF NOT EXISTS hdr_enabled BOOLEAN NOT NULL DEFAULT FALSE");
        jdbcTemplate.execute("ALTER TABLE t_calibration_global_setting " +
                "ADD COLUMN IF NOT EXISTS hdr_dark_calibration_id BIGINT REFERENCES t_calibration_session(id) ON DELETE SET NULL");
        jdbcTemplate.execute("ALTER TABLE t_calibration_global_setting " +
                "ADD COLUMN IF NOT EXISTS hdr_flat_calibration_id BIGINT REFERENCES t_calibration_session(id) ON DELETE SET NULL");
        jdbcTemplate.execute("ALTER TABLE t_calibration_global_setting " +
                "ADD COLUMN IF NOT EXISTS hdr_defect_map_enabled BOOLEAN NOT NULL DEFAULT FALSE");
    }

    /** 获取当前用户选择的校准包和其多帧缺陷地图状态。 */
    public CalibrationGlobalSettingsResponse getGlobalSettings(Long userId) {
        GlobalSetting setting = findGlobalSetting(userId);
        SessionReference dark = findReferenceById(userId, setting.darkCalibrationId, "DARK");
        SessionReference flat = findReferenceById(userId, setting.flatCalibrationId, "FLAT");
        boolean packageReady = referencesMatch(dark, flat);
        SpectralMultiFrameQualityAnalysisService.DefectMap defectMap = packageReady
                ? combineDefectMaps(dark, flat)
                : null;
        HdrSessionReference hdrDark = findHdrReferenceById(userId, setting.hdrDarkCalibrationId, "HDR_DARK");
        HdrSessionReference hdrFlat = findHdrReferenceById(userId, setting.hdrFlatCalibrationId, "HDR_FLAT");
        boolean hdrPackageReady = hdrReferencesMatch(hdrDark, hdrFlat);
        HdrDefectMaps hdrDefectMaps = hdrPackageReady ? combineHdrDefectMaps(hdrDark, hdrFlat) : null;
        CalibrationGlobalSettingsResponse response = new CalibrationGlobalSettingsResponse();
        response.setEnabled(setting.enabled);
        response.setDarkCalibrationId(setting.darkCalibrationId);
        response.setFlatCalibrationId(setting.flatCalibrationId);
        response.setDefectMapEnabled(setting.defectMapEnabled);
        response.setCalibrationPackageReady(packageReady);
        response.setDefectMapAvailable(defectMap != null && hasDefectEntries(defectMap));
        response.setHdrEnabled(setting.hdrEnabled);
        response.setHdrDarkCalibrationId(setting.hdrDarkCalibrationId);
        response.setHdrFlatCalibrationId(setting.hdrFlatCalibrationId);
        response.setHdrDefectMapEnabled(setting.hdrDefectMapEnabled);
        response.setHdrCalibrationPackageReady(hdrPackageReady);
        response.setHdrDefectMapAvailable(hdrDefectMaps != null && hdrDefectMaps.hasEntries());
        response.setWidth(packageReady ? dark.session.getWidth() : null);
        response.setHeight(packageReady ? dark.session.getHeight() : null);
        response.setUpdatedAt(setting.updatedAt);
        response.setDarkReferenceAvailable(dark != null);
        response.setFlatReferenceAvailable(flat != null);
        response.setHdrDarkReferenceAvailable(hdrDark != null);
        response.setHdrFlatReferenceAvailable(hdrFlat != null);
        if (!setting.enabled) {
            response.setMessage("当前未启用校准包：后续采集沿用原始 RAW 流程。请选择一组暗场和平场会话后再启用。");
        } else if (!packageReady) {
            response.setMessage("校准包未就绪：必须选择同尺寸、READY 状态的暗场和平场会话，当前帧将退回原始 RAW。");
        } else if (setting.defectMapEnabled && response.isDefectMapAvailable()) {
            response.setMessage("校准包已启用：采集时先应用锁定的暗场/平场参考，再使用该包的多帧稳定缺陷地图。");
        } else if (setting.defectMapEnabled) {
            response.setMessage("校准包已启用，但所选会话没有有效缺陷条目；仍会执行暗场/平场校正。");
        } else {
            response.setMessage("校准包已启用：采集时执行锁定的暗场/平场校正，但不应用多帧缺陷地图。");
        }
        return response;
    }

    /** 保存当前用户选择的校准包，不影响既有图像、参考图和原始 RAW。 */
    @Transactional
    public CalibrationGlobalSettingsResponse updateGlobalSettings(Long userId,
                                                                   CalibrationGlobalSettingsRequest request) {
        if (request == null || request.getEnabled() == null) {
            throw new IllegalArgumentException("校准包启用状态不能为空");
        }
        GlobalSetting previous = findGlobalSetting(userId);
        boolean hdrEnabled = request.getHdrEnabled() == null
                ? previous.hdrEnabled
                : Boolean.TRUE.equals(request.getHdrEnabled());
        Long hdrDarkCalibrationId = request.getHdrDarkCalibrationId() == null
                ? previous.hdrDarkCalibrationId
                : request.getHdrDarkCalibrationId();
        Long hdrFlatCalibrationId = request.getHdrFlatCalibrationId() == null
                ? previous.hdrFlatCalibrationId
                : request.getHdrFlatCalibrationId();
        boolean requestedHdrDefectMapEnabled = request.getHdrDefectMapEnabled() == null
                ? previous.hdrDefectMapEnabled
                : Boolean.TRUE.equals(request.getHdrDefectMapEnabled());
        if (Boolean.TRUE.equals(request.getEnabled())) {
            SessionReference dark = findReferenceById(userId, request.getDarkCalibrationId(), "DARK");
            SessionReference flat = findReferenceById(userId, request.getFlatCalibrationId(), "FLAT");
            if (!referencesMatch(dark, flat)) {
                throw new IllegalArgumentException("启用校准包必须选择同尺寸且 READY 的暗场、平场会话");
            }
            ensureCalibrationQualityUsable(dark.session, "暗场");
            ensureCalibrationQualityUsable(flat.session, "平场");
        }
        if (hdrEnabled) {
            HdrSessionReference hdrDark = findHdrReferenceById(userId, hdrDarkCalibrationId, "HDR_DARK");
            HdrSessionReference hdrFlat = findHdrReferenceById(userId, hdrFlatCalibrationId, "HDR_FLAT");
            if (!hdrReferencesMatch(hdrDark, hdrFlat)) {
                throw new IllegalArgumentException("启用HDR校准包必须选择同尺寸且 READY 的 HDR暗场、HDR平场会话");
            }
            ensureCalibrationQualityUsable(hdrDark.session, "HDR暗场");
            ensureCalibrationQualityUsable(hdrFlat.session, "HDR平场");
        }
        boolean storedDefectMapEnabled = Boolean.TRUE.equals(request.getEnabled())
                && Boolean.TRUE.equals(request.getDefectMapEnabled());
        boolean storedHdrDefectMapEnabled = hdrEnabled && requestedHdrDefectMapEnabled;
        jdbcTemplate.update(
                "INSERT INTO t_calibration_global_setting(" +
                        "user_id, enabled, dark_calibration_id, flat_calibration_id, defect_map_enabled, " +
                        "hdr_enabled, hdr_dark_calibration_id, hdr_flat_calibration_id, hdr_defect_map_enabled, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP) " +
                        "ON CONFLICT (user_id) DO UPDATE SET " +
                        "enabled=EXCLUDED.enabled, dark_calibration_id=EXCLUDED.dark_calibration_id, " +
                        "flat_calibration_id=EXCLUDED.flat_calibration_id, " +
                        "defect_map_enabled=EXCLUDED.defect_map_enabled, " +
                        "hdr_enabled=EXCLUDED.hdr_enabled, " +
                        "hdr_dark_calibration_id=EXCLUDED.hdr_dark_calibration_id, " +
                        "hdr_flat_calibration_id=EXCLUDED.hdr_flat_calibration_id, " +
                        "hdr_defect_map_enabled=EXCLUDED.hdr_defect_map_enabled, updated_at=CURRENT_TIMESTAMP",
                userId,
                request.getEnabled(),
                request.getDarkCalibrationId(),
                request.getFlatCalibrationId(),
                storedDefectMapEnabled,
                hdrEnabled,
                hdrDarkCalibrationId,
                hdrFlatCalibrationId,
                storedHdrDefectMapEnabled);
        return getGlobalSettings(userId);
    }

    @Transactional
    public CalibrationSessionResponse generateSimulated(Long userId, String calibrationType, CalibrationRequest request) {
        int frameCount = normalizeFrameCount(request == null ? null : request.getFrameCount());
        int width = normalizeDimension(request == null ? null : request.getWidth(), DEFAULT_WIDTH);
        int height = normalizeDimension(request == null ? null : request.getHeight(), DEFAULT_HEIGHT);
        String type = normalizeType(calibrationType);
        long sessionId = createSession(userId, type, "SIMULATED", frameCount, width, height);
        Path directory = buildCalibrationDirectory(type, sessionId);
        try {
            Files.createDirectories(directory);
            if (isHdrCalibrationType(type)) {
                List<short[]> hgFrames = new ArrayList<>();
                List<short[]> lgFrames = new ArrayList<>();
                Random random = new Random(20260711L + sessionId);
                for (int frameIndex = 0; frameIndex < frameCount; frameIndex++) {
                    short[] hgPixels = buildSimulatedHdrPlane(type, "HG", width, height, random, frameIndex);
                    short[] lgPixels = buildSimulatedHdrPlane(type, "LG", width, height, random, frameIndex);
                    hgFrames.add(hgPixels);
                    lgFrames.add(lgPixels);
                    writeHdrCalibrationSampleFiles(directory, frameIndex + 1, width, height, hgPixels, lgPixels);
                }
                return finishHdrSession(
                        sessionId,
                        type,
                        "SIMULATED",
                        frameCount,
                        width,
                        height,
                        directory,
                        hgFrames,
                        lgFrames,
                        "HDR模拟校准数据已生成，可在真实CMOS连接后用硬件采集会话替换。");
            }
            List<short[]> frames = new ArrayList<>();
            Random random = new Random(20260711L + sessionId);
            for (int frameIndex = 0; frameIndex < frameCount; frameIndex++) {
                short[] pixels = buildSimulatedFrame(type, width, height, random, frameIndex);
                frames.add(pixels);
                writeRaw(directory.resolve(String.format("frame-%03d.raw16le.bin", frameIndex + 1)), pixels);
                writePreview(width, height, pixels, directory.resolve(String.format("frame-%03d.png", frameIndex + 1)));
            }
            return finishSession(
                    sessionId,
                    type,
                    "SIMULATED",
                    frameCount,
                    width,
                    height,
                    directory,
                    frames,
                    "模拟校准数据已生成，可在真实CMOS连接后用硬件采集会话替换。");
        } catch (RuntimeException | IOException ex) {
            failSession(sessionId, ex.getMessage());
            deleteDirectoryQuietly(directory);
            throw new IllegalStateException("生成模拟校准数据失败: " + ex.getMessage(), ex);
        }
    }

    /** 使用已经保存的普通图像构建暗场/平场校准会话，便于真实硬件接入前测试。 */
    @Transactional
    public CalibrationSessionResponse buildFromImages(Long userId,
                                                      String calibrationType,
                                                      CalibrationRequest request) {
        String type = normalizeType(calibrationType);
        List<Long> imageIds = request == null || request.getImageIds() == null
                ? Collections.<Long>emptyList()
                : request.getImageIds();
        if (imageIds.size() < 2) {
            throw new IllegalArgumentException("构建校准会话至少需要2张已保存图像");
        }

        List<SourceImage> sources = findUserImages(userId, imageIds);
        if (sources.size() != imageIds.size()) {
            throw new IllegalArgumentException("部分图像不存在、尺寸不一致或不属于当前用户");
        }
        int width = sources.get(0).width;
        int height = sources.get(0).height;
        for (SourceImage source : sources) {
            if (source.width != width || source.height != height) {
                throw new IllegalArgumentException("构建校准会话的图像尺寸必须一致");
            }
            if (!type.equals(source.captureScene)) {
                throw new IllegalArgumentException("构建" + calibrationTypeLabel(type)
                        + "校准会话只能使用" + type + "场景采集帧，图像ID "
                        + source.id + " 的场景为 " + source.captureScene);
            }
            if (isHdrCalibrationType(type)
                    && (source.hgRawStorageUri == null || source.lgRawStorageUri == null)) {
                throw new IllegalArgumentException("HDR校准会话必须使用包含HG/LG原始平面的HDR样本，图像ID "
                        + source.id + " 缺少HG或LG RAW文件");
            }
        }

        long sessionId = createSession(userId, type, "IMAGES", sources.size(), width, height);
        Path directory = buildCalibrationDirectory(type, sessionId);
        try {
            Files.createDirectories(directory);
            if (isHdrCalibrationType(type)) {
                List<short[]> hgFrames = new ArrayList<>();
                List<short[]> lgFrames = new ArrayList<>();
                for (int index = 0; index < sources.size(); index++) {
                    SourceImage source = sources.get(index);
                    short[] hgPixels = readRaw(resolveStorageUri(source.hgRawStorageUri), width * height);
                    short[] lgPixels = readRaw(resolveStorageUri(source.lgRawStorageUri), width * height);
                    hgFrames.add(hgPixels);
                    lgFrames.add(lgPixels);
                    writeHdrCalibrationSampleFiles(directory, index + 1, width, height, hgPixels, lgPixels);
                }
                return finishHdrSession(
                        sessionId,
                        type,
                        "IMAGES",
                        hgFrames.size(),
                        width,
                        height,
                        directory,
                        hgFrames,
                        lgFrames,
                        "已使用已保存HDR双平面样本构建校准会话；真实CMOS采集时可在此模块重新采集替换。");
            }
            List<short[]> frames = new ArrayList<>();
            for (int index = 0; index < sources.size(); index++) {
                SourceImage source = sources.get(index);
                short[] pixels = readRaw(resolveStorageUri(source.rawStorageUri), width * height);
                frames.add(pixels);
                writeRaw(directory.resolve(String.format("frame-%03d.raw16le.bin", index + 1)), pixels);
                writePreview(width, height, pixels, directory.resolve(String.format("frame-%03d.png", index + 1)));
            }
            return finishSession(
                    sessionId,
                    type,
                    "IMAGES",
                    frames.size(),
                    width,
                    height,
                    directory,
                    frames,
                    "已使用已保存图像构建校准会话；真实CMOS采集时可在此模块重新采集替换。");
        } catch (RuntimeException | IOException ex) {
            failSession(sessionId, ex.getMessage());
            deleteDirectoryQuietly(directory);
            throw new IllegalStateException("构建校准会话失败: " + ex.getMessage(), ex);
        }
    }

    public List<CalibrationSessionResponse> list(Long userId, String calibrationType) {
        String type = calibrationType == null ? null : normalizeType(calibrationType);
        String sql = "SELECT id, session_number, calibration_type, acquisition_mode, status, expected_frame_count, frame_count, " +
                "width, height, storage_uri, defect_map_uri, bad_pixel_count, bad_row_count, " +
                "bad_column_count, summary, message, created_at, completed_at " +
                "FROM t_calibration_session WHERE user_id=? " +
                (type == null ? "" : "AND calibration_type=? ") +
                "ORDER BY created_at DESC";
        List<Object> args = new ArrayList<>();
        args.add(userId);
        if (type != null) {
            args.add(type);
        }
        return jdbcTemplate.query(sql, (resultSet, rowNum) -> mapSession(resultSet), args.toArray());
    }

    public CalibrationSessionResponse get(Long userId, long sessionId) {
        List<CalibrationSessionResponse> sessions = jdbcTemplate.query(
                "SELECT id, session_number, calibration_type, acquisition_mode, status, expected_frame_count, frame_count, " +
                        "width, height, storage_uri, defect_map_uri, bad_pixel_count, bad_row_count, " +
                        "bad_column_count, summary, message, created_at, completed_at " +
                        "FROM t_calibration_session WHERE user_id=? AND id=?",
                (resultSet, rowNum) -> mapSession(resultSet),
                userId,
                sessionId);
        if (sessions.isEmpty()) {
            throw new IllegalArgumentException("校准会话不存在或不属于当前用户");
        }
        return sessions.get(0);
    }

    /** 读取校准会话中的PNG预览。前端按该会话实际帧数请求，因此采集多少张即可预览多少张。 */
    public List<CalibrationPreviewResponse> listPreviews(Long userId, long sessionId, int requestedLimit) {
        CalibrationSessionResponse session = get(userId, sessionId);
        if (!"READY".equals(session.getStatus()) || session.getStorageUri() == null) {
            return Collections.emptyList();
        }
        int limit = requestedLimit <= 0 ? session.getFrameCount() : Math.max(1, requestedLimit);
        int count = Math.min(session.getFrameCount(), limit);
        Path directory = resolveStorageUri(session.getStorageUri());
        List<CalibrationPreviewResponse> previews = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            if (isHdrCalibrationType(session.getCalibrationType())) {
                int beforeCount = previews.size();
                addSamplePreview(
                        previews,
                        directory,
                        index,
                        "HG_SAMPLE",
                        "第 " + index + " 帧 HG 原始校准样本",
                        String.format("frame-%03d-hg.png", index));
                addSamplePreview(
                        previews,
                        directory,
                        index,
                        "LG_SAMPLE",
                        "第 " + index + " 帧 LG 原始校准样本",
                        String.format("frame-%03d-lg.png", index));
                // 兼容旧版本HDR校准包：旧包可能只保存了 frame-xxx.png 诊断合成预览，
                // 没有按 HG/LG 分开的原始样本图。此时仍返回 SAMPLE，避免前端预览为空。
                if (previews.size() == beforeCount) {
                    addSamplePreview(
                            previews,
                            directory,
                            index,
                            "SAMPLE",
                            "第 " + index + " 帧 HDR 诊断合成校准样本",
                            String.format("frame-%03d.png", index));
                }
                continue;
            }
            Path previewFile = directory.resolve(String.format("frame-%03d.png", index)).normalize();
            addSamplePreview(
                    previews,
                    directory,
                    index,
                    "SAMPLE",
                    "第 " + index + " 帧原始校准样本",
                    previewFile.getFileName().toString());
        }
        return previews;
    }

    /** 读取校准会话最终参考图预览；这张参考图才是后续正式图像校准时实际使用的数据。 */
    public CalibrationPreviewResponse getReferencePreview(Long userId, long sessionId) {
        List<CalibrationPreviewResponse> previews = listReferencePreviews(userId, sessionId);
        return previews.isEmpty() ? null : previews.get(0);
    }

    private void addSamplePreview(List<CalibrationPreviewResponse> previews,
                                  Path directory,
                                  int frameIndex,
                                  String previewType,
                                  String label,
                                  String filename) {
        Path previewFile = directory.resolve(filename).normalize();
        if (!previewFile.startsWith(directory) || !Files.exists(previewFile)) {
            return;
        }
        try {
            CalibrationPreviewResponse response = new CalibrationPreviewResponse();
            response.setFrameIndex(frameIndex);
            response.setPreviewType(previewType);
            response.setLabel(label);
            response.setImageDataUrl(encodePreviewDataUrl(previewFile));
            response.setStorageUri(toStorageUri(previewFile));
            previews.add(response);
        } catch (RuntimeException ex) {
            // 单张预览读取失败不影响其余预览。
        }
    }

    /** 读取最终参考图预览。普通校准返回一张，HDR校准返回HG/LG两张。 */
    public List<CalibrationPreviewResponse> listReferencePreviews(Long userId, long sessionId) {
        CalibrationSessionResponse session = get(userId, sessionId);
        if (!"READY".equals(session.getStatus()) || session.getStorageUri() == null) {
            return Collections.emptyList();
        }
        Path directory = resolveStorageUri(session.getStorageUri());
        Map<String, Object> summary = session.getSummary() == null
                ? Collections.<String, Object>emptyMap()
                : session.getSummary();
        if (isHdrCalibrationType(session.getCalibrationType())) {
            List<CalibrationPreviewResponse> previews = new ArrayList<>();
            addReferencePreview(
                    previews,
                    session,
                    directory,
                    stringValue(summary.get("hgReferenceStorageUri"),
                            toStorageUri(directory.resolve("hg-reference.raw16le.bin"))),
                    stringValue(summary.get("hgReferencePreviewStorageUri"),
                            toStorageUri(directory.resolve("hg-reference.png"))),
                    "HDR_DARK".equals(session.getCalibrationType()) ? "最终HG暗场参考图" : "最终HG平场参考图",
                    "HG_REFERENCE");
            addReferencePreview(
                    previews,
                    session,
                    directory,
                    stringValue(summary.get("lgReferenceStorageUri"),
                            toStorageUri(directory.resolve("lg-reference.raw16le.bin"))),
                    stringValue(summary.get("lgReferencePreviewStorageUri"),
                            toStorageUri(directory.resolve("lg-reference.png"))),
                    "HDR_DARK".equals(session.getCalibrationType()) ? "最终LG暗场参考图" : "最终LG平场参考图",
                    "LG_REFERENCE");
            return previews;
        }
        String referenceUri = summary.get("referenceStorageUri") == null
                ? toStorageUri(directory.resolve("reference.raw16le.bin"))
                : String.valueOf(summary.get("referenceStorageUri"));
        Path referenceFile = resolveStorageUri(referenceUri);
        Path previewFile = summary.get("referencePreviewStorageUri") == null
                ? directory.resolve("reference.png").normalize()
                : resolveStorageUri(String.valueOf(summary.get("referencePreviewStorageUri")));
        if (!previewFile.startsWith(directory)) {
            previewFile = directory.resolve("reference.png").normalize();
        }
        if (!referenceFile.startsWith(directory) || !Files.exists(referenceFile)) {
            return null;
        }
        try {
            if (!Files.exists(previewFile)) {
                short[] referencePixels = readRaw(referenceFile, session.getWidth() * session.getHeight());
                writePreview(session.getWidth(), session.getHeight(), referencePixels, previewFile);
            }
            CalibrationPreviewResponse response = new CalibrationPreviewResponse();
            response.setFrameIndex(0);
            response.setPreviewType("REFERENCE");
            response.setLabel("DARK".equals(session.getCalibrationType())
                    ? "最终暗场参考图"
                    : "最终平场参考图");
            response.setImageDataUrl(encodePreviewDataUrl(previewFile));
            response.setStorageUri(toStorageUri(referenceFile));
            return Collections.singletonList(response);
        } catch (IOException ex) {
            throw new IllegalStateException("读取最终校准参考图失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 删除某个历史暗场/平场校准包。
     *
     * <p>这里删除的是校准会话本身和它生成的参考图、缺陷地图、样本副本文件；已经用过该包的
     * 普通光谱图像不会被删除，它们的 quality details 里保留了采集当时的校准包编号快照。
     * 后续打开图像预览时，图像持久化服务会再判断这些编号是否还能在校准表中找到，并给出
     * “历史校准包已删除”的审计标注。</p>
     */
    @Transactional
    public boolean delete(Long userId, long sessionId) {
        CalibrationSessionResponse session = get(userId, sessionId);
        String storageUri = session.getStorageUri();
        Path directory = storageUri == null || storageUri.trim().isEmpty()
                ? null
                : resolveStorageUri(storageUri);
        int deleted = jdbcTemplate.update(
                "DELETE FROM t_calibration_session WHERE user_id=? AND id=?",
                userId,
                sessionId);
        if (deleted > 0) {
            jdbcTemplate.update(
                    "UPDATE t_calibration_global_setting " +
                            "SET enabled=CASE WHEN dark_calibration_id IS NULL OR flat_calibration_id IS NULL THEN FALSE ELSE enabled END, " +
                            "defect_map_enabled=CASE WHEN dark_calibration_id IS NULL OR flat_calibration_id IS NULL THEN FALSE ELSE defect_map_enabled END, " +
                            "hdr_enabled=CASE WHEN hdr_dark_calibration_id IS NULL OR hdr_flat_calibration_id IS NULL THEN FALSE ELSE hdr_enabled END, " +
                            "hdr_defect_map_enabled=CASE WHEN hdr_dark_calibration_id IS NULL OR hdr_flat_calibration_id IS NULL THEN FALSE ELSE hdr_defect_map_enabled END, " +
                            "updated_at=CURRENT_TIMESTAMP " +
                            "WHERE user_id=? AND (dark_calibration_id IS NULL OR flat_calibration_id IS NULL " +
                            "OR hdr_dark_calibration_id IS NULL OR hdr_flat_calibration_id IS NULL)",
                    userId);
            deleteDirectoryQuietly(directory);
        }
        return deleted > 0;
    }

    /** 读取当前用户某类校准的最新缺陷地图，用于后续单帧修复。 */
    public SpectralMultiFrameQualityAnalysisService.DefectMap latestDefectMap(Long userId,
                                                                               String calibrationType) {
        List<CalibrationSessionResponse> sessions = list(userId, calibrationType);
        for (CalibrationSessionResponse session : sessions) {
            if (!"READY".equals(session.getStatus())) {
                continue;
            }
            Map<String, Object> summary = session.getSummary();
            return SpectralMultiFrameQualityAnalysisService.DefectMap.fromPersisted(
                    session.getWidth(),
                    session.getHeight(),
                    number(summary.get("voteRatio"), 0.6d),
                    integerList(summary.get("badPixelIndexes")),
                    integerList(summary.get("abnormalRows")),
                    integerList(summary.get("abnormalColumns")));
        }
        throw new IllegalStateException("当前没有可用的" + normalizeType(calibrationType) + "校准地图");
    }

    /** 加载新采集帧使用的当前校准包。当前设置只影响新帧，不会改写旧帧的校准版本。 */
    public CalibrationProfile loadActiveProfile(Long userId, int width, int height) {
        GlobalSetting setting = findGlobalSetting(userId);
        return loadProfileBySessionIds(
                userId,
                width,
                height,
                setting.enabled,
                setting.darkCalibrationId,
                setting.flatCalibrationId,
                setting.defectMapEnabled,
                "当前校准包");
    }

    /**
     * 从图像质量详情中恢复采集时锁定的校准包。这样后续处理、复检与光谱提取不会误用最新配置。
     */
    @SuppressWarnings("unchecked")
    public CalibrationProfile loadCapturedProfile(Long userId,
                                                  int width,
                                                  int height,
                                                  Map<String, Object> qualityDetails) {
        Object calibrationValue = qualityDetails == null ? null : qualityDetails.get("calibration");
        if (!(calibrationValue instanceof Map)) {
            return identityProfile(width, height, "历史图像未保存校准包快照，按原始 RAW 处理");
        }
        Map<String, Object> snapshot = (Map<String, Object>) calibrationValue;
        boolean enabled = Boolean.TRUE.equals(snapshot.get("calibrationPackageEnabled"))
                || Boolean.TRUE.equals(snapshot.get("globalCalibrationEnabled"));
        return loadProfileBySessionIds(
                userId,
                width,
                height,
                enabled,
                longValue(snapshot.get("darkCalibrationId")),
                longValue(snapshot.get("flatCalibrationId")),
                Boolean.TRUE.equals(snapshot.get("defectMapEnabled")),
                "采集时校准包快照");
    }

    /** 对一张图像执行全局暗场扣除和平场增益校正。 */
    public CalibrationApplicationResult applyActiveCalibration(Long userId,
                                                                int width,
                                                                int height,
                                                                short[] pixels16) {
        return loadActiveProfile(userId, width, height).apply(pixels16);
    }

    /** 加载HDR模式专用校准包。HDR校准发生在HG/LG融合之前。 */
    public HdrCalibrationProfile loadActiveHdrProfile(Long userId, int width, int height) {
        GlobalSetting setting = findGlobalSetting(userId);
        return loadHdrProfileBySessionIds(
                userId,
                width,
                height,
                setting.hdrEnabled,
                setting.hdrDarkCalibrationId,
                setting.hdrFlatCalibrationId,
                setting.hdrDefectMapEnabled,
                "当前HDR校准包");
    }

    private GlobalSetting findGlobalSetting(Long userId) {
        List<GlobalSetting> settings = jdbcTemplate.query(
                "SELECT enabled, dark_calibration_id, flat_calibration_id, defect_map_enabled, " +
                        "hdr_enabled, hdr_dark_calibration_id, hdr_flat_calibration_id, hdr_defect_map_enabled, updated_at " +
                        "FROM t_calibration_global_setting WHERE user_id=?",
                (resultSet, rowNum) -> new GlobalSetting(
                        resultSet.getBoolean("enabled"),
                        (Long) resultSet.getObject("dark_calibration_id"),
                        (Long) resultSet.getObject("flat_calibration_id"),
                        resultSet.getBoolean("defect_map_enabled"),
                        resultSet.getBoolean("hdr_enabled"),
                        (Long) resultSet.getObject("hdr_dark_calibration_id"),
                        (Long) resultSet.getObject("hdr_flat_calibration_id"),
                        resultSet.getBoolean("hdr_defect_map_enabled"),
                        resultSet.getObject("updated_at", OffsetDateTime.class)),
                userId);
        return settings.isEmpty() ? new GlobalSetting(false, null, null, false, false, null, null, false, null) : settings.get(0);
    }

    private CalibrationProfile loadProfileBySessionIds(Long userId,
                                                       int width,
                                                       int height,
                                                       boolean enabled,
                                                       Long darkSessionId,
                                                       Long flatSessionId,
                                                       boolean defectMapEnabled,
                                                       String sourceLabel) {
        if (!enabled) {
            return identityProfile(width, height, sourceLabel + "未启用，使用原始 RAW");
        }
        SessionReference dark = findReferenceById(userId, darkSessionId, "DARK");
        SessionReference flat = findReferenceById(userId, flatSessionId, "FLAT");
        if (!referencesMatch(dark, flat)
                || dark.session.getWidth() != width
                || dark.session.getHeight() != height) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("calibrationPackageEnabled", true);
            details.put("calibrationApplied", false);
            details.put("darkCalibrationId", darkSessionId);
            details.put("flatCalibrationId", flatSessionId);
            putSessionAudit(details, "dark", dark);
            putSessionAudit(details, "flat", flat);
            details.put("defectMapEnabled", false);
            details.put("message", sourceLabel + "不存在、未就绪或与当前图像尺寸不匹配，使用原始 RAW");
            return new CalibrationProfile(width, height, null, null, 1.0d, null, details);
        }
        short[] darkReference = readReference(dark, width * height);
        short[] flatReference = readReference(flat, width * height);
        if (darkReference == null || flatReference == null) {
            return identityProfile(width, height, sourceLabel + "参考文件无法读取，使用原始 RAW");
        }
        double flatBase = calculateFlatBase(flatReference, darkReference);
        SpectralMultiFrameQualityAnalysisService.DefectMap defectMap = defectMapEnabled
                ? combineDefectMaps(dark, flat)
                : null;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("calibrationPackageEnabled", true);
        details.put("calibrationApplied", true);
        putSessionAudit(details, "dark", dark);
        putSessionAudit(details, "flat", flat);
        details.put("darkReferenceUri", dark.referenceUri);
        details.put("flatReferenceUri", flat.referenceUri);
        details.put("flatBaseDn", flatBase);
        details.put("defectMapEnabled", defectMapEnabled && defectMap != null && hasDefectEntries(defectMap));
        details.put("defectMapSource", "DARK+FLAT multi-frame union");
        details.put("defectMapBadPixelCount", defectMap == null ? 0 : defectMap.getBadPixelIndexes().size());
        details.put("defectMapAbnormalRowCount", defectMap == null ? 0 : defectMap.getAbnormalRows().size());
        details.put("defectMapAbnormalColumnCount", defectMap == null ? 0 : defectMap.getAbnormalColumns().size());
        details.put("message", defectMap != null && hasDefectEntries(defectMap)
                ? sourceLabel + "已锁定暗场/平场参考及多帧缺陷地图"
                : sourceLabel + "已锁定暗场/平场参考，未应用缺陷地图");
        return new CalibrationProfile(width, height, darkReference, flatReference, flatBase, defectMap, details);
    }

    private HdrCalibrationProfile loadHdrProfileBySessionIds(Long userId,
                                                             int width,
                                                             int height,
                                                             boolean enabled,
                                                             Long hdrDarkSessionId,
                                                             Long hdrFlatSessionId,
                                                             boolean defectMapEnabled,
                                                             String sourceLabel) {
        if (!enabled) {
            return identityHdrProfile(width, height, sourceLabel + "未启用，HDR采集使用未校准HG/LG平面融合");
        }
        HdrSessionReference dark = findHdrReferenceById(userId, hdrDarkSessionId, "HDR_DARK");
        HdrSessionReference flat = findHdrReferenceById(userId, hdrFlatSessionId, "HDR_FLAT");
        if (!hdrReferencesMatch(dark, flat)
                || dark.session.getWidth() != width
                || dark.session.getHeight() != height) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("calibrationPackageEnabled", true);
            details.put("hdrCalibrationPackageEnabled", true);
            details.put("calibrationApplied", false);
            details.put("defectMapEnabled", false);
            details.put("defectMapApplied", false);
            details.put("hdrDarkCalibrationId", hdrDarkSessionId);
            details.put("hdrFlatCalibrationId", hdrFlatSessionId);
            putHdrSessionAudit(details, "hdrDark", dark);
            putHdrSessionAudit(details, "hdrFlat", flat);
            details.put("message", sourceLabel + "不存在、未就绪或与当前HDR图像尺寸不匹配，使用未校准HG/LG融合");
            return new HdrCalibrationProfile(width, height, null, null, null, null,
                    1.0d, 1.0d, null, details, imageProcessingService);
        }

        int pixelCount = width * height;
        short[] hgDarkReference = readReferenceUri(dark.hgReferenceUri, pixelCount);
        short[] lgDarkReference = readReferenceUri(dark.lgReferenceUri, pixelCount);
        short[] hgFlatReference = readReferenceUri(flat.hgReferenceUri, pixelCount);
        short[] lgFlatReference = readReferenceUri(flat.lgReferenceUri, pixelCount);
        if (hgDarkReference == null || lgDarkReference == null
                || hgFlatReference == null || lgFlatReference == null) {
            return identityHdrProfile(width, height, sourceLabel + "HG/LG参考文件无法读取，使用未校准HG/LG融合");
        }

        double hgFlatBase = calculateFlatBase(hgFlatReference, hgDarkReference);
        double lgFlatBase = calculateFlatBase(lgFlatReference, lgDarkReference);
        HdrDefectMaps defectMaps = defectMapEnabled ? combineHdrDefectMaps(dark, flat) : null;

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("calibrationPackageEnabled", true);
        details.put("hdrCalibrationPackageEnabled", true);
        details.put("calibrationApplied", true);
        details.put("calibrationOrder", "HG/LG dark subtraction + flat correction + plane defect repair, then HDR fusion");
        putHdrSessionAudit(details, "hdrDark", dark);
        putHdrSessionAudit(details, "hdrFlat", flat);
        details.put("hgDarkReferenceUri", dark.hgReferenceUri);
        details.put("lgDarkReferenceUri", dark.lgReferenceUri);
        details.put("hgFlatReferenceUri", flat.hgReferenceUri);
        details.put("lgFlatReferenceUri", flat.lgReferenceUri);
        details.put("hgFlatBaseDn", hgFlatBase);
        details.put("lgFlatBaseDn", lgFlatBase);
        details.put("defectMapEnabled", defectMapEnabled && defectMaps != null && defectMaps.hasEntries());
        details.put("defectMapSource", "HDR_DARK+HDR_FLAT multi-frame union, separated by HG/LG plane");
        details.put("hgDefectMapBadPixelCount", defectMaps == null || defectMaps.hgMap == null
                ? 0 : defectMaps.hgMap.getBadPixelIndexes().size());
        details.put("hgDefectMapAbnormalRowCount", defectMaps == null || defectMaps.hgMap == null
                ? 0 : defectMaps.hgMap.getAbnormalRows().size());
        details.put("hgDefectMapAbnormalColumnCount", defectMaps == null || defectMaps.hgMap == null
                ? 0 : defectMaps.hgMap.getAbnormalColumns().size());
        details.put("lgDefectMapBadPixelCount", defectMaps == null || defectMaps.lgMap == null
                ? 0 : defectMaps.lgMap.getBadPixelIndexes().size());
        details.put("lgDefectMapAbnormalRowCount", defectMaps == null || defectMaps.lgMap == null
                ? 0 : defectMaps.lgMap.getAbnormalRows().size());
        details.put("lgDefectMapAbnormalColumnCount", defectMaps == null || defectMaps.lgMap == null
                ? 0 : defectMaps.lgMap.getAbnormalColumns().size());
        details.put("message", defectMaps != null && defectMaps.hasEntries()
                ? sourceLabel + "已锁定HG/LG暗场、HG/LG平场参考及分平面稳定缺陷地图"
                : sourceLabel + "已锁定HG/LG暗场、HG/LG平场参考，未应用HDR缺陷地图");

        return new HdrCalibrationProfile(
                width,
                height,
                hgDarkReference,
                lgDarkReference,
                hgFlatReference,
                lgFlatReference,
                hgFlatBase,
                lgFlatBase,
                defectMaps,
                details,
                imageProcessingService);
    }

    private HdrCalibrationProfile identityHdrProfile(int width, int height, String message) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("calibrationPackageEnabled", false);
        details.put("hdrCalibrationPackageEnabled", false);
        details.put("calibrationApplied", false);
        details.put("defectMapEnabled", false);
        details.put("defectMapApplied", false);
        details.put("message", message);
        return new HdrCalibrationProfile(width, height, null, null, null, null,
                1.0d, 1.0d, null, details, imageProcessingService);
    }

    private void putSessionAudit(Map<String, Object> details, String prefix, SessionReference reference) {
        if (reference == null || reference.session == null) {
            return;
        }
        CalibrationSessionResponse session = reference.session;
        String type = session.getCalibrationType();
        String labelPrefix = "DARK".equals(type) ? "D" : "F";
        Integer sessionNumber = session.getSessionNumber();
        details.put(prefix + "CalibrationId", session.getId());
        details.put(prefix + "SessionNumber", sessionNumber);
        details.put(prefix + "CalibrationLabel",
                sessionNumber == null ? null : labelPrefix + "-" + String.format("%03d", sessionNumber));
        details.put(prefix + "CalibrationType", type);
        details.put(prefix + "CalibrationMode", session.getAcquisitionMode());
        details.put(prefix + "CalibrationFrameCount", session.getFrameCount());
        details.put(prefix + "CalibrationCreatedAt",
                session.getCreatedAt() == null ? null : session.getCreatedAt().toString());
    }

    private void putHdrSessionAudit(Map<String, Object> details, String prefix, HdrSessionReference reference) {
        if (reference == null || reference.session == null) {
            return;
        }
        CalibrationSessionResponse session = reference.session;
        String labelPrefix = "HDR_DARK".equals(session.getCalibrationType()) ? "HD" : "HF";
        Integer sessionNumber = session.getSessionNumber();
        details.put(prefix + "CalibrationId", session.getId());
        details.put(prefix + "SessionNumber", sessionNumber);
        details.put(prefix + "CalibrationLabel",
                sessionNumber == null ? null : labelPrefix + "-" + String.format("%03d", sessionNumber));
        details.put(prefix + "CalibrationType", session.getCalibrationType());
        details.put(prefix + "CalibrationMode", session.getAcquisitionMode());
        details.put(prefix + "CalibrationFrameCount", session.getFrameCount());
        details.put(prefix + "CalibrationCreatedAt",
                session.getCreatedAt() == null ? null : session.getCreatedAt().toString());
    }

    private CalibrationProfile identityProfile(int width, int height, String message) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("calibrationPackageEnabled", false);
        details.put("calibrationApplied", false);
        details.put("defectMapEnabled", false);
        details.put("message", message);
        return new CalibrationProfile(width, height, null, null, 1.0d, null, details);
    }

    private SessionReference findReferenceById(Long userId, Long sessionId, String expectedType) {
        if (sessionId == null) {
            return null;
        }
        try {
            CalibrationSessionResponse session = get(userId, sessionId);
            if (!expectedType.equals(session.getCalibrationType()) || !"READY".equals(session.getStatus())) {
                return null;
            }
            Map<String, Object> summary = session.getSummary() == null
                    ? Collections.<String, Object>emptyMap()
                    : session.getSummary();
            String referenceUri = summary.get("referenceStorageUri") == null
                    ? (session.getStorageUri() == null
                        ? null
                        : toStorageUri(resolveStorageUri(session.getStorageUri()).resolve("reference.raw16le.bin")))
                    : String.valueOf(summary.get("referenceStorageUri"));
            if (referenceUri == null || !Files.exists(resolveStorageUri(referenceUri))) {
                return null;
            }
            return new SessionReference(session, referenceUri);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private HdrSessionReference findHdrReferenceById(Long userId, Long sessionId, String expectedType) {
        if (sessionId == null) {
            return null;
        }
        try {
            CalibrationSessionResponse session = get(userId, sessionId);
            if (!expectedType.equals(session.getCalibrationType()) || !"READY".equals(session.getStatus())) {
                return null;
            }
            Map<String, Object> summary = session.getSummary() == null
                    ? Collections.<String, Object>emptyMap()
                    : session.getSummary();
            if (session.getStorageUri() == null) {
                return null;
            }
            Path directory = resolveStorageUri(session.getStorageUri());
            String hgReferenceUri = stringValue(
                    summary.get("hgReferenceStorageUri"),
                    toStorageUri(directory.resolve("hg-reference.raw16le.bin")));
            String lgReferenceUri = stringValue(
                    summary.get("lgReferenceStorageUri"),
                    toStorageUri(directory.resolve("lg-reference.raw16le.bin")));
            if (!Files.exists(resolveStorageUri(hgReferenceUri))
                    || !Files.exists(resolveStorageUri(lgReferenceUri))) {
                return null;
            }
            return new HdrSessionReference(session, hgReferenceUri, lgReferenceUri);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private boolean referencesMatch(SessionReference dark, SessionReference flat) {
        return dark != null && flat != null
                && dark.session.getWidth() != null
                && dark.session.getHeight() != null
                && dark.session.getWidth().equals(flat.session.getWidth())
                && dark.session.getHeight().equals(flat.session.getHeight());
    }

    private boolean hdrReferencesMatch(HdrSessionReference dark, HdrSessionReference flat) {
        return dark != null && flat != null
                && dark.session.getWidth() != null
                && dark.session.getHeight() != null
                && dark.session.getWidth().equals(flat.session.getWidth())
                && dark.session.getHeight().equals(flat.session.getHeight());
    }

    private SpectralMultiFrameQualityAnalysisService.DefectMap combineDefectMaps(
            SessionReference dark,
            SessionReference flat) {
        if (!referencesMatch(dark, flat)) {
            return null;
        }
        SpectralMultiFrameQualityAnalysisService.DefectMap darkMap = defectMapFromSession(dark.session);
        SpectralMultiFrameQualityAnalysisService.DefectMap flatMap = defectMapFromSession(flat.session);
        java.util.Set<Integer> pixels = new java.util.LinkedHashSet<>();
        java.util.Set<Integer> rows = new java.util.LinkedHashSet<>();
        java.util.Set<Integer> columns = new java.util.LinkedHashSet<>();
        if (darkMap != null) {
            pixels.addAll(darkMap.getBadPixelIndexes());
            rows.addAll(darkMap.getAbnormalRows());
            columns.addAll(darkMap.getAbnormalColumns());
        }
        if (flatMap != null) {
            pixels.addAll(flatMap.getBadPixelIndexes());
            rows.addAll(flatMap.getAbnormalRows());
            columns.addAll(flatMap.getAbnormalColumns());
        }
        return SpectralMultiFrameQualityAnalysisService.DefectMap.fromPersisted(
                dark.session.getWidth(),
                dark.session.getHeight(),
                0.6d,
                new ArrayList<>(pixels),
                new ArrayList<>(rows),
                new ArrayList<>(columns));
    }

    private HdrDefectMaps combineHdrDefectMaps(HdrSessionReference dark,
                                               HdrSessionReference flat) {
        if (!hdrReferencesMatch(dark, flat)) {
            return null;
        }
        SpectralMultiFrameQualityAnalysisService.DefectMap darkHg = hdrDefectMapFromSession(dark.session, "hg");
        SpectralMultiFrameQualityAnalysisService.DefectMap darkLg = hdrDefectMapFromSession(dark.session, "lg");
        SpectralMultiFrameQualityAnalysisService.DefectMap flatHg = hdrDefectMapFromSession(flat.session, "hg");
        SpectralMultiFrameQualityAnalysisService.DefectMap flatLg = hdrDefectMapFromSession(flat.session, "lg");
        return new HdrDefectMaps(
                unionDefectMaps(dark.session.getWidth(), dark.session.getHeight(), darkHg, flatHg),
                unionDefectMaps(dark.session.getWidth(), dark.session.getHeight(), darkLg, flatLg));
    }

    private SpectralMultiFrameQualityAnalysisService.DefectMap unionDefectMaps(
            int width,
            int height,
            SpectralMultiFrameQualityAnalysisService.DefectMap first,
            SpectralMultiFrameQualityAnalysisService.DefectMap second) {
        java.util.Set<Integer> pixels = new java.util.LinkedHashSet<>();
        java.util.Set<Integer> rows = new java.util.LinkedHashSet<>();
        java.util.Set<Integer> columns = new java.util.LinkedHashSet<>();
        if (first != null) {
            pixels.addAll(first.getBadPixelIndexes());
            rows.addAll(first.getAbnormalRows());
            columns.addAll(first.getAbnormalColumns());
        }
        if (second != null) {
            pixels.addAll(second.getBadPixelIndexes());
            rows.addAll(second.getAbnormalRows());
            columns.addAll(second.getAbnormalColumns());
        }
        return SpectralMultiFrameQualityAnalysisService.DefectMap.fromPersisted(
                width,
                height,
                0.6d,
                new ArrayList<>(pixels),
                new ArrayList<>(rows),
                new ArrayList<>(columns));
    }

    private SpectralMultiFrameQualityAnalysisService.DefectMap defectMapFromSession(
            CalibrationSessionResponse session) {
        if (session == null || session.getSummary() == null) {
            return null;
        }
        Map<String, Object> summary = session.getSummary();
        return SpectralMultiFrameQualityAnalysisService.DefectMap.fromPersisted(
                session.getWidth(),
                session.getHeight(),
                number(summary.get("voteRatio"), 0.6d),
                integerList(summary.get("badPixelIndexes")),
                integerList(summary.get("abnormalRows")),
                integerList(summary.get("abnormalColumns")));
    }

    private SpectralMultiFrameQualityAnalysisService.DefectMap hdrDefectMapFromSession(
            CalibrationSessionResponse session,
            String planePrefix) {
        if (session == null || session.getSummary() == null) {
            return null;
        }
        Map<String, Object> summary = session.getSummary();
        return SpectralMultiFrameQualityAnalysisService.DefectMap.fromPersisted(
                session.getWidth(),
                session.getHeight(),
                number(summary.get(planePrefix + "VoteRatio"), 0.6d),
                integerList(summary.get(planePrefix + "BadPixelIndexes")),
                integerList(summary.get(planePrefix + "AbnormalRows")),
                integerList(summary.get(planePrefix + "AbnormalColumns")));
    }

    private boolean hasDefectEntries(SpectralMultiFrameQualityAnalysisService.DefectMap defectMap) {
        return defectMap != null && (!defectMap.getBadPixelIndexes().isEmpty()
                || !defectMap.getAbnormalRows().isEmpty()
                || !defectMap.getAbnormalColumns().isEmpty());
    }

    private Long longValue(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : null;
    }

    private SessionReference findLatestReference(List<CalibrationSessionResponse> sessions,
                                                 int width,
                                                 int height) {
        for (CalibrationSessionResponse session : sessions) {
            if (!"READY".equals(session.getStatus())
                    || session.getWidth() == null
                    || session.getHeight() == null
                    || session.getWidth() != width
                    || session.getHeight() != height
                    || session.getStorageUri() == null) {
                continue;
            }
            Map<String, Object> summary = session.getSummary() == null
                    ? Collections.<String, Object>emptyMap()
                    : session.getSummary();
            String referenceUri = summary.get("referenceStorageUri") == null
                    ? toStorageUri(resolveStorageUri(session.getStorageUri()).resolve("reference.raw16le.bin"))
                    : String.valueOf(summary.get("referenceStorageUri"));
            Path referenceFile = resolveStorageUri(referenceUri);
            if (Files.exists(referenceFile)) {
                return new SessionReference(session, referenceUri);
            }
        }
        return null;
    }

    private short[] readReference(SessionReference reference, int expectedPixels) {
        if (reference == null) {
            return null;
        }
        try {
            return readRaw(resolveStorageUri(reference.referenceUri), expectedPixels);
        } catch (IOException ex) {
            // 校准文件损坏时不阻塞正式采集，当前帧退回未校准分析并在结果中体现。
            return null;
        }
    }

    private short[] readReferenceUri(String referenceUri, int expectedPixels) {
        if (referenceUri == null || referenceUri.trim().isEmpty()) {
            return null;
        }
        try {
            return readRaw(resolveStorageUri(referenceUri), expectedPixels);
        } catch (IOException ex) {
            return null;
        }
    }

    private double calculateFlatBase(short[] flatReference, short[] darkReference) {
        int sampleCount = (flatReference.length + 15) / 16;
        int[] samples = new int[sampleCount];
        int count = 0;
        for (int index = 0; index < flatReference.length; index += 16) {
            int dark = darkReference == null ? 0 : darkReference[index] & SENSOR_MAX_DN;
            samples[count++] = Math.max(1, (flatReference[index] & SENSOR_MAX_DN) - dark);
        }
        Arrays.sort(samples, 0, count);
        int middle = count / 2;
        if ((count & 1) == 1) {
            return samples[middle];
        }
        return (samples[middle - 1] + samples[middle]) / 2.0d;
    }

    private CalibrationSessionResponse finishSession(long sessionId,
                                                      String type,
                                                      String mode,
                                                      int frameCount,
                                                      int width,
                                                      int height,
                                                      Path directory,
                                                      List<short[]> frames,
                                                      String message) throws IOException {
        SpectralMultiFrameQualityAnalysisService.MultiFrameResult result = multiFrameService.analyze(
                width,
                height,
                frames,
                0.6d);
        Map<String, Object> summary = new LinkedHashMap<>(result.getDetails());
        summary.put("badPixelCount", result.getBadPixelIndexes().size());
        summary.put("abnormalRowCount", result.getAbnormalRows().size());
        summary.put("abnormalColumnCount", result.getAbnormalColumns().size());
        summary.put("badPixelIndexes", result.getBadPixelIndexes());
        summary.put("abnormalRows", result.getAbnormalRows());
        summary.put("abnormalColumns", result.getAbnormalColumns());
        short[] reference = buildMedianReference(frames);
        SpectralCalibrationQualityAnalysisService.CalibrationQualityResult calibrationQuality =
                calibrationQualityAnalysisService.analyze(type, width, height, frames, reference, result);
        summary.put("calibrationQuality", calibrationQuality.toMap());
        Path referenceFile = directory.resolve("reference.raw16le.bin");
        writeRaw(referenceFile, reference);
        summary.put("referenceStorageUri", toStorageUri(referenceFile));
        Path referencePreviewFile = directory.resolve("reference.png");
        writePreview(width, height, reference, referencePreviewFile);
        summary.put("referencePreviewStorageUri", toStorageUri(referencePreviewFile));
        Path qualityFile = directory.resolve("calibration-quality.json");
        summary.put("calibrationQualityUri", toStorageUri(qualityFile));
        Files.write(qualityFile, toJson(calibrationQuality.toMap()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path mapFile = directory.resolve("defect-map.json");
        Files.write(mapFile, toJson(summary).getBytes(java.nio.charset.StandardCharsets.UTF_8));

        jdbcTemplate.update(
                "UPDATE t_calibration_session SET status='READY', frame_count=?, storage_uri=?, defect_map_uri=?, " +
                        "bad_pixel_count=?, bad_row_count=?, bad_column_count=?, summary=CAST(? AS jsonb), " +
                        "message=?, completed_at=CURRENT_TIMESTAMP WHERE id=?",
                frameCount,
                toStorageUri(directory),
                toStorageUri(mapFile),
                result.getBadPixelIndexes().size(),
                result.getAbnormalRows().size(),
                result.getAbnormalColumns().size(),
                toJson(summary),
                message + " " + calibrationQuality.getSummaryMessage() + "；" + result.getSummaryMessage(),
                sessionId);
        return getAnyOwnerSession(sessionId);
    }

    private CalibrationSessionResponse finishHdrSession(long sessionId,
                                                         String type,
                                                         String mode,
                                                         int frameCount,
                                                         int width,
                                                         int height,
                                                         Path directory,
                                                         List<short[]> hgFrames,
                                                         List<short[]> lgFrames,
                                                         String message) throws IOException {
        SpectralMultiFrameQualityAnalysisService.MultiFrameResult hgResult =
                multiFrameService.analyze(width, height, hgFrames, 0.6d);
        SpectralMultiFrameQualityAnalysisService.MultiFrameResult lgResult =
                multiFrameService.analyze(width, height, lgFrames, 0.6d);
        short[] hgReference = buildMedianReference(hgFrames);
        short[] lgReference = buildMedianReference(lgFrames);

        SpectralCalibrationQualityAnalysisService.CalibrationQualityResult hgQuality =
                calibrationQualityAnalysisService.analyze(type, width, height, hgFrames, hgReference, hgResult);
        SpectralCalibrationQualityAnalysisService.CalibrationQualityResult lgQuality =
                calibrationQualityAnalysisService.analyze(type, width, height, lgFrames, lgReference, lgResult);
        Map<String, Object> combinedQuality = combineHdrCalibrationQuality(hgQuality, lgQuality);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("algorithmVersion", "hdr-calibration-session-v1");
        summary.put("calibrationType", type);
        summary.put("underlyingCalibrationType", underlyingCalibrationType(type));
        summary.put("frameCount", frameCount);
        summary.put("payloadLayout", "HG_FULL_FRAME_THEN_LG_FULL_FRAME");
        summary.put("referencePolicy", "per-plane median reference; HG/LG are never fused before calibration");

        putPlaneResultSummary(summary, "hg", hgResult);
        putPlaneResultSummary(summary, "lg", lgResult);
        summary.put("badPixelCount", uniqueCount(
                hgResult.getBadPixelIndexes(),
                lgResult.getBadPixelIndexes()));
        summary.put("abnormalRowCount", uniqueCount(
                hgResult.getAbnormalRows(),
                lgResult.getAbnormalRows()));
        summary.put("abnormalColumnCount", uniqueCount(
                hgResult.getAbnormalColumns(),
                lgResult.getAbnormalColumns()));

        Path hgReferenceFile = directory.resolve("hg-reference.raw16le.bin");
        Path lgReferenceFile = directory.resolve("lg-reference.raw16le.bin");
        writeRaw(hgReferenceFile, hgReference);
        writeRaw(lgReferenceFile, lgReference);
        summary.put("hgReferenceStorageUri", toStorageUri(hgReferenceFile));
        summary.put("lgReferenceStorageUri", toStorageUri(lgReferenceFile));

        Path hgReferencePreviewFile = directory.resolve("hg-reference.png");
        Path lgReferencePreviewFile = directory.resolve("lg-reference.png");
        writePreview(width, height, hgReference, hgReferencePreviewFile);
        writePreview(width, height, lgReference, lgReferencePreviewFile);
        summary.put("hgReferencePreviewStorageUri", toStorageUri(hgReferencePreviewFile));
        summary.put("lgReferencePreviewStorageUri", toStorageUri(lgReferencePreviewFile));

        summary.put("hgCalibrationQuality", hgQuality.toMap());
        summary.put("lgCalibrationQuality", lgQuality.toMap());
        summary.put("calibrationQuality", combinedQuality);
        Path qualityFile = directory.resolve("calibration-quality.json");
        summary.put("calibrationQualityUri", toStorageUri(qualityFile));
        Files.write(qualityFile, toJson(combinedQuality).getBytes(java.nio.charset.StandardCharsets.UTF_8));

        Path mapFile = directory.resolve("defect-map.json");
        Files.write(mapFile, toJson(summary).getBytes(java.nio.charset.StandardCharsets.UTF_8));

        jdbcTemplate.update(
                "UPDATE t_calibration_session SET status='READY', frame_count=?, storage_uri=?, defect_map_uri=?, " +
                        "bad_pixel_count=?, bad_row_count=?, bad_column_count=?, summary=CAST(? AS jsonb), " +
                        "message=?, completed_at=CURRENT_TIMESTAMP WHERE id=?",
                frameCount,
                toStorageUri(directory),
                toStorageUri(mapFile),
                uniqueCount(hgResult.getBadPixelIndexes(), lgResult.getBadPixelIndexes()),
                uniqueCount(hgResult.getAbnormalRows(), lgResult.getAbnormalRows()),
                uniqueCount(hgResult.getAbnormalColumns(), lgResult.getAbnormalColumns()),
                toJson(summary),
                message + " HG：" + hgQuality.getSummaryMessage()
                        + "；LG：" + lgQuality.getSummaryMessage()
                        + "；HG " + hgResult.getSummaryMessage()
                        + "；LG " + lgResult.getSummaryMessage(),
                sessionId);
        return getAnyOwnerSession(sessionId);
    }

    private void putPlaneResultSummary(Map<String, Object> summary,
                                       String planePrefix,
                                       SpectralMultiFrameQualityAnalysisService.MultiFrameResult result) {
        summary.put(planePrefix + "VoteRatio", result.getVoteRatio());
        summary.put(planePrefix + "RequiredVotes", result.getDetails().get("requiredVotes"));
        summary.put(planePrefix + "BadPixelCount", result.getBadPixelIndexes().size());
        summary.put(planePrefix + "AbnormalRowCount", result.getAbnormalRows().size());
        summary.put(planePrefix + "AbnormalColumnCount", result.getAbnormalColumns().size());
        summary.put(planePrefix + "BadPixelIndexes", result.getBadPixelIndexes());
        summary.put(planePrefix + "AbnormalRows", result.getAbnormalRows());
        summary.put(planePrefix + "AbnormalColumns", result.getAbnormalColumns());
        summary.put(planePrefix + "DefectDetails", result.getDetails());
    }

    private Map<String, Object> combineHdrCalibrationQuality(
            SpectralCalibrationQualityAnalysisService.CalibrationQualityResult hgQuality,
            SpectralCalibrationQualityAnalysisService.CalibrationQualityResult lgQuality) {
        Map<String, Object> combined = new LinkedHashMap<>();
        String status = moreSevereQualityStatus(hgQuality.getQualityStatus(), lgQuality.getQualityStatus());
        combined.put("analysisVersion", "hdr-calibration-quality-v1");
        combined.put("qualityStatus", status);
        combined.put("summaryMessage", "HDR校准质量：" + status
                + "；HG " + hgQuality.getSummaryMessage()
                + "；LG " + lgQuality.getSummaryMessage());
        combined.put("hgQuality", hgQuality.toMap());
        combined.put("lgQuality", lgQuality.toMap());
        List<String> reasons = new ArrayList<>();
        reasons.add("HG: " + hgQuality.getSummaryMessage());
        reasons.add("LG: " + lgQuality.getSummaryMessage());
        combined.put("reasonMessages", reasons);
        return combined;
    }

    private void ensureCalibrationQualityUsable(CalibrationSessionResponse session, String label) {
        Map<String, Object> summary = session == null ? null : session.getSummary();
        Map<String, Object> quality = summary == null ? null : asMap(summary.get("calibrationQuality"));
        if (quality == null) {
            return;
        }
        Object statusValue = quality.get("qualityStatus");
        String status = statusValue == null ? null : String.valueOf(statusValue);
        if ("FAIL".equals(status)) {
            Object messageValue = quality.get("summaryMessage");
            String message = messageValue == null ? "校准样本质量为 FAIL" : String.valueOf(messageValue);
            throw new IllegalArgumentException(label + "校准样本质量为 FAIL，不允许启用该校准包：" + message);
        }
    }

    private short[] buildMedianReference(List<short[]> frames) {
        int pixelCount = frames.get(0).length;
        short[] reference = new short[pixelCount];
        int[] values = new int[frames.size()];
        for (int pixelIndex = 0; pixelIndex < pixelCount; pixelIndex++) {
            for (int frameIndex = 0; frameIndex < frames.size(); frameIndex++) {
                values[frameIndex] = frames.get(frameIndex)[pixelIndex] & SENSOR_MAX_DN;
            }
            Arrays.sort(values);
            int middle = values.length / 2;
            int median = (values.length & 1) == 1
                    ? values[middle]
                    : (values[middle - 1] + values[middle]) / 2;
            reference[pixelIndex] = (short) clamp(median);
        }
        return reference;
    }

    private CalibrationSessionResponse getAnyOwnerSession(long sessionId) {
        List<CalibrationSessionResponse> sessions = jdbcTemplate.query(
                "SELECT id, session_number, calibration_type, acquisition_mode, status, expected_frame_count, frame_count, " +
                        "width, height, storage_uri, defect_map_uri, bad_pixel_count, bad_row_count, " +
                        "bad_column_count, summary, message, created_at, completed_at " +
                        "FROM t_calibration_session WHERE id=?",
                (resultSet, rowNum) -> mapSession(resultSet),
                sessionId);
        if (sessions.isEmpty()) {
            throw new IllegalStateException("保存校准会话后无法重新读取");
        }
        return sessions.get(0);
    }

    private long createSession(Long userId,
                               String type,
                               String mode,
                               int frameCount,
                               int width,
                               int height) {
        Integer sessionNumber = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(session_number), 0) + 1 FROM t_calibration_session " +
                        "WHERE user_id=? AND calibration_type=?",
                Integer.class,
                userId,
                type);
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO t_calibration_session " +
                        "(user_id, session_number, calibration_type, acquisition_mode, status, expected_frame_count, width, height) " +
                        "VALUES (?, ?, ?, ?, 'PROCESSING', ?, ?, ?) RETURNING id",
                Long.class,
                userId,
                sessionNumber,
                type,
                mode,
                frameCount,
                width,
                height);
        if (id == null) {
            throw new IllegalStateException("创建校准会话失败");
        }
        return id;
    }

    private short[] buildSimulatedFrame(String type,
                                        int width,
                                        int height,
                                        Random random,
                                        int frameIndex) {
        short[] pixels = new short[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int value;
                if ("DARK".equals(type)) {
                    value = 10 + random.nextInt(5);
                } else {
                    int horizontal = (x * 40) / Math.max(1, width - 1);
                    int vertical = (y * 25) / Math.max(1, height - 1);
                    value = 1800 + horizontal - vertical + random.nextInt(17) - 8;
                }
                pixels[y * width + x] = (short) clamp(value);
            }
        }

        // 稳定注入少量缺陷，方便在没有CMOS时验证多帧投票与修复流程。
        int badPixelCount = Math.min(80, Math.max(8, (width * height) / 8000));
        for (int index = 0; index < badPixelCount; index++) {
            int x = 40 + ((index * 83) % Math.max(1, width - 80));
            int y = 30 + ((index * 47) % Math.max(1, height - 60));
            pixels[y * width + x] = (short) (index % 2 == 0 ? 4095 : 0);
        }
        int badRow = Math.max(2, height / 3);
        int badColumn = Math.max(2, width / 2);
        for (int x = 0; x < width; x++) {
            pixels[badRow * width + x] = (short) clamp("DARK".equals(type) ? 180 : 2450);
        }
        for (int y = 0; y < height; y++) {
            pixels[y * width + badColumn] = (short) clamp("DARK".equals(type) ? 0 : 1200);
        }
        // 让每一帧仍有轻微变化，验证算法不是简单比较完全相同的图片。
        if (frameIndex % 2 == 1 && width > 10 && height > 10) {
            pixels[10 * width + 10] = (short) clamp((pixels[10 * width + 10] & SENSOR_MAX_DN) + 2);
        }
        return pixels;
    }

    private short[] buildSimulatedHdrPlane(String type,
                                           String plane,
                                           int width,
                                           int height,
                                           Random random,
                                           int frameIndex) {
        String baseType = underlyingCalibrationType(type);
        short[] pixels = buildSimulatedFrame(baseType, width, height, random, frameIndex);
        double scale;
        if ("DARK".equals(baseType)) {
            scale = "HG".equals(plane) ? 1.35d : 0.75d;
        } else {
            scale = "HG".equals(plane) ? 1.45d : 0.55d;
        }
        for (int index = 0; index < pixels.length; index++) {
            int value = pixels[index] & SENSOR_MAX_DN;
            int jitter = random.nextInt(5) - 2;
            pixels[index] = (short) clamp((int) Math.round(value * scale) + jitter);
        }
        return pixels;
    }

    private void writeHdrCalibrationSampleFiles(Path directory,
                                                int frameIndex,
                                                int width,
                                                int height,
                                                short[] hgPixels,
                                                short[] lgPixels) throws IOException {
        writeRaw(directory.resolve(String.format("frame-%03d-hg.raw16le.bin", frameIndex)), hgPixels);
        writeRaw(directory.resolve(String.format("frame-%03d-lg.raw16le.bin", frameIndex)), lgPixels);
        writePreview(width, height, hgPixels, directory.resolve(String.format("frame-%03d-hg.png", frameIndex)));
        writePreview(width, height, lgPixels, directory.resolve(String.format("frame-%03d-lg.png", frameIndex)));
        short[] diagnostic = buildHdrDiagnosticComposite(hgPixels, lgPixels);
        writeRaw(directory.resolve(String.format("frame-%03d.raw16le.bin", frameIndex)), diagnostic);
        writePreview(width, height, diagnostic, directory.resolve(String.format("frame-%03d.png", frameIndex)));
    }

    private short[] buildHdrDiagnosticComposite(short[] hgPixels, short[] lgPixels) {
        short[] diagnostic = new short[hgPixels.length];
        for (int index = 0; index < hgPixels.length; index++) {
            int hg = hgPixels[index] & SENSOR_MAX_DN;
            int lg = lgPixels[index] & SENSOR_MAX_DN;
            diagnostic[index] = (short) Math.max(hg, lg);
        }
        return diagnostic;
    }

    private List<SourceImage> findUserImages(Long userId, List<Long> imageIds) {
        String placeholders = String.join(",", Collections.nCopies(imageIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.addAll(imageIds);
        return jdbcTemplate.query(
                "SELECT i.id, i.width, i.height, i.raw_storage_uri, " +
                        "i.hg_raw_storage_uri, i.lg_raw_storage_uri, c.capture_scene " +
                        "FROM t_spectral_image i JOIN t_spectral_capture c ON c.id=i.capture_id " +
                        "WHERE c.user_id=? AND i.id IN (" + placeholders + ") ORDER BY i.received_at ASC",
                (resultSet, rowNum) -> new SourceImage(
                        resultSet.getLong("id"),
                        resultSet.getInt("width"),
                        resultSet.getInt("height"),
                        resultSet.getString("raw_storage_uri"),
                        resultSet.getString("hg_raw_storage_uri"),
                        resultSet.getString("lg_raw_storage_uri"),
                        resultSet.getString("capture_scene")),
                args.toArray());
    }

    private CalibrationSessionResponse mapSession(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        CalibrationSessionResponse response = new CalibrationSessionResponse();
        response.setId(resultSet.getLong("id"));
        response.setSessionNumber(resultSet.getInt("session_number"));
        response.setCalibrationType(resultSet.getString("calibration_type"));
        response.setAcquisitionMode(resultSet.getString("acquisition_mode"));
        response.setStatus(resultSet.getString("status"));
        response.setExpectedFrameCount(resultSet.getInt("expected_frame_count"));
        response.setFrameCount(resultSet.getInt("frame_count"));
        response.setWidth(resultSet.getInt("width"));
        response.setHeight(resultSet.getInt("height"));
        response.setStorageUri(resultSet.getString("storage_uri"));
        response.setDefectMapUri(resultSet.getString("defect_map_uri"));
        response.setBadPixelCount(resultSet.getInt("bad_pixel_count"));
        response.setBadRowCount(resultSet.getInt("bad_row_count"));
        response.setBadColumnCount(resultSet.getInt("bad_column_count"));
        response.setSummary(parseJson(resultSet.getString("summary")));
        response.setMessage(resultSet.getString("message"));
        response.setCreatedAt(resultSet.getObject("created_at", OffsetDateTime.class));
        response.setCompletedAt(resultSet.getObject("completed_at", OffsetDateTime.class));
        return response;
    }

    private String normalizeType(String value) {
        String type = value == null ? "" : value.trim().toUpperCase();
        if ("CALIBRATION_HDR_DARK".equals(type) || "HDR_CALIBRATION_DARK".equals(type)) {
            return "HDR_DARK";
        }
        if ("CALIBRATION_HDR_FLAT".equals(type) || "HDR_CALIBRATION_FLAT".equals(type)) {
            return "HDR_FLAT";
        }
        if (!"DARK".equals(type)
                && !"FLAT".equals(type)
                && !"HDR_DARK".equals(type)
                && !"HDR_FLAT".equals(type)) {
            throw new IllegalArgumentException("校准类型必须是DARK、FLAT、HDR_DARK或HDR_FLAT");
        }
        return type;
    }

    private boolean isHdrCalibrationType(String type) {
        return "HDR_DARK".equals(type) || "HDR_FLAT".equals(type);
    }

    private String underlyingCalibrationType(String type) {
        return "HDR_DARK".equals(type) ? "DARK"
                : "HDR_FLAT".equals(type) ? "FLAT"
                : type;
    }

    private String calibrationTypeLabel(String type) {
        if ("DARK".equals(type)) {
            return "暗场";
        }
        if ("FLAT".equals(type)) {
            return "平场";
        }
        if ("HDR_DARK".equals(type)) {
            return "HDR暗场";
        }
        if ("HDR_FLAT".equals(type)) {
            return "HDR平场";
        }
        return type;
    }

    private int normalizeFrameCount(Integer value) {
        int count = value == null ? 16 : value;
        if (count < 2 || count > 64) {
            throw new IllegalArgumentException("帧数必须在2到64之间");
        }
        return count;
    }

    private int normalizeDimension(Integer value, int fallback) {
        int dimension = value == null ? fallback : value;
        if (dimension < 3 || dimension > 4096) {
            throw new IllegalArgumentException("图像尺寸超出允许范围");
        }
        return dimension;
    }

    private int clamp(int value) {
        return Math.max(0, Math.min(SENSOR_MAX_DN, value));
    }

    private Path buildCalibrationDirectory(String type, long sessionId) {
        return getStorageRoot().resolve("calibrations").resolve(type.toLowerCase()).resolve(String.valueOf(sessionId));
    }

    private Path getStorageRoot() {
        return Paths.get(storageRoot).toAbsolutePath().normalize();
    }

    private String toStorageUri(Path file) {
        return getStorageRoot().relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private Path resolveStorageUri(String uri) {
        Path resolved = getStorageRoot().resolve(uri).normalize();
        if (!resolved.startsWith(getStorageRoot())) {
            throw new IllegalArgumentException("非法校准文件路径");
        }
        return resolved;
    }

    private void writeRaw(Path file, short[] pixels) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(pixels.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short pixel : pixels) {
            buffer.putShort((short) (pixel & SENSOR_MAX_DN));
        }
        Files.write(file, buffer.array());
    }

    private short[] readRaw(Path file, int expectedPixels) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length != expectedPixels * 2) {
            throw new IOException("RAW文件大小与图像尺寸不一致");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        short[] pixels = new short[expectedPixels];
        for (int index = 0; index < expectedPixels; index++) {
            pixels[index] = (short) (buffer.getShort() & SENSOR_MAX_DN);
        }
        return pixels;
    }

    private void writePreview(int width, int height, short[] pixels, Path file) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        byte[] target = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        for (int index = 0; index < pixels.length; index++) {
            target[index] = (byte) (((pixels[index] & SENSOR_MAX_DN) * 255) / SENSOR_MAX_DN);
        }
        if (!ImageIO.write(image, "png", file.toFile())) {
            throw new IOException("当前JRE没有可用的PNG编码器");
        }
    }

    private Map<String, Object> parseJson(String json) {
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
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map) {
            return new LinkedHashMap<>((Map<String, Object>) value);
        }
        return null;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("校准结果JSON序列化失败", ex);
        }
    }

    private String encodePreviewDataUrl(Path previewFile) {
        try {
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(previewFile));
        } catch (IOException ex) {
            throw new IllegalStateException("读取校准预览失败: " + ex.getMessage(), ex);
        }
    }

    private void addReferencePreview(List<CalibrationPreviewResponse> previews,
                                     CalibrationSessionResponse session,
                                     Path directory,
                                     String referenceUri,
                                     String previewUri,
                                     String label,
                                     String previewType) {
        Path referenceFile = resolveStorageUri(referenceUri);
        Path previewFile = resolveStorageUri(previewUri);
        if (!referenceFile.startsWith(directory) || !Files.exists(referenceFile)) {
            return;
        }
        if (!previewFile.startsWith(directory)) {
            previewFile = directory.resolve(previewType.toLowerCase() + ".png").normalize();
        }
        try {
            if (!Files.exists(previewFile)) {
                short[] referencePixels = readRaw(referenceFile, session.getWidth() * session.getHeight());
                writePreview(session.getWidth(), session.getHeight(), referencePixels, previewFile);
            }
            CalibrationPreviewResponse response = new CalibrationPreviewResponse();
            response.setFrameIndex(0);
            response.setPreviewType(previewType);
            response.setLabel(label);
            response.setImageDataUrl(encodePreviewDataUrl(previewFile));
            response.setStorageUri(toStorageUri(referenceFile));
            previews.add(response);
        } catch (IOException ex) {
            throw new IllegalStateException("读取HDR最终校准参考图失败: " + ex.getMessage(), ex);
        }
    }

    private String stringValue(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private int uniqueCount(List<Integer> first, List<Integer> second) {
        java.util.Set<Integer> values = new java.util.LinkedHashSet<>();
        if (first != null) {
            values.addAll(first);
        }
        if (second != null) {
            values.addAll(second);
        }
        return values.size();
    }

    private String moreSevereQualityStatus(String first, String second) {
        return qualitySeverity(second) > qualitySeverity(first) ? second : first;
    }

    private int qualitySeverity(String status) {
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

    private double number(Object value, double fallback) {
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    private List<Integer> integerList(Object value) {
        if (!(value instanceof List)) {
            return Collections.emptyList();
        }
        List<Integer> result = new ArrayList<>();
        for (Object item : (List<?>) value) {
            if (item instanceof Number) {
                result.add(((Number) item).intValue());
            }
        }
        return result;
    }

    private void failSession(long sessionId, String message) {
        jdbcTemplate.update(
                "UPDATE t_calibration_session SET status='FAILED', message=?, completed_at=CURRENT_TIMESTAMP WHERE id=?",
                message == null ? "未知错误" : message,
                sessionId);
    }

    private void deleteDirectoryQuietly(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            paths.sorted((left, right) -> right.compareTo(left)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 补偿清理失败不覆盖数据库错误。
                }
            });
        } catch (IOException ignored) {
            // 由后续运维清理孤儿目录。
        }
    }

    private static final class GlobalSetting {
        private final boolean enabled;
        private final Long darkCalibrationId;
        private final Long flatCalibrationId;
        private final boolean defectMapEnabled;
        private final boolean hdrEnabled;
        private final Long hdrDarkCalibrationId;
        private final Long hdrFlatCalibrationId;
        private final boolean hdrDefectMapEnabled;
        private final OffsetDateTime updatedAt;

        private GlobalSetting(boolean enabled,
                              Long darkCalibrationId,
                              Long flatCalibrationId,
                              boolean defectMapEnabled,
                              boolean hdrEnabled,
                              Long hdrDarkCalibrationId,
                              Long hdrFlatCalibrationId,
                              boolean hdrDefectMapEnabled,
                              OffsetDateTime updatedAt) {
            this.enabled = enabled;
            this.darkCalibrationId = darkCalibrationId;
            this.flatCalibrationId = flatCalibrationId;
            this.defectMapEnabled = defectMapEnabled;
            this.hdrEnabled = hdrEnabled;
            this.hdrDarkCalibrationId = hdrDarkCalibrationId;
            this.hdrFlatCalibrationId = hdrFlatCalibrationId;
            this.hdrDefectMapEnabled = hdrDefectMapEnabled;
            this.updatedAt = updatedAt;
        }
    }

    private static final class SourceImage {
        private final long id;
        private final int width;
        private final int height;
        private final String rawStorageUri;
        private final String hgRawStorageUri;
        private final String lgRawStorageUri;
        private final String captureScene;

        private SourceImage(long id,
                            int width,
                            int height,
                            String rawStorageUri,
                            String hgRawStorageUri,
                            String lgRawStorageUri,
                            String captureScene) {
            this.id = id;
            this.width = width;
            this.height = height;
            this.rawStorageUri = rawStorageUri;
            this.hgRawStorageUri = hgRawStorageUri;
            this.lgRawStorageUri = lgRawStorageUri;
            this.captureScene = captureScene;
        }
    }

    private static final class SessionReference {
        private final CalibrationSessionResponse session;
        private final String referenceUri;

        private SessionReference(CalibrationSessionResponse session, String referenceUri) {
            this.session = session;
            this.referenceUri = referenceUri;
        }
    }

    private static final class HdrSessionReference {
        private final CalibrationSessionResponse session;
        private final String hgReferenceUri;
        private final String lgReferenceUri;

        private HdrSessionReference(CalibrationSessionResponse session,
                                    String hgReferenceUri,
                                    String lgReferenceUri) {
            this.session = session;
            this.hgReferenceUri = hgReferenceUri;
            this.lgReferenceUri = lgReferenceUri;
        }
    }

    private static final class HdrDefectMaps {
        private final SpectralMultiFrameQualityAnalysisService.DefectMap hgMap;
        private final SpectralMultiFrameQualityAnalysisService.DefectMap lgMap;

        private HdrDefectMaps(SpectralMultiFrameQualityAnalysisService.DefectMap hgMap,
                              SpectralMultiFrameQualityAnalysisService.DefectMap lgMap) {
            this.hgMap = hgMap;
            this.lgMap = lgMap;
        }

        private boolean hasEntries() {
            return hasEntries(hgMap) || hasEntries(lgMap);
        }

        private static boolean hasEntries(SpectralMultiFrameQualityAnalysisService.DefectMap map) {
            return map != null && (!map.getBadPixelIndexes().isEmpty()
                    || !map.getAbnormalRows().isEmpty()
                    || !map.getAbnormalColumns().isEmpty());
        }
    }

    @lombok.Getter
    public static final class HdrCalibrationProfile {
        private final int width;
        private final int height;
        private final short[] hgDarkReference;
        private final short[] lgDarkReference;
        private final short[] hgFlatReference;
        private final short[] lgFlatReference;
        private final double hgFlatBase;
        private final double lgFlatBase;
        private final HdrDefectMaps defectMaps;
        private final Map<String, Object> details;
        private final SpectralImageProcessingService imageProcessingService;

        private HdrCalibrationProfile(int width,
                                      int height,
                                      short[] hgDarkReference,
                                      short[] lgDarkReference,
                                      short[] hgFlatReference,
                                      short[] lgFlatReference,
                                      double hgFlatBase,
                                      double lgFlatBase,
                                      HdrDefectMaps defectMaps,
                                      Map<String, Object> details,
                                      SpectralImageProcessingService imageProcessingService) {
            this.width = width;
            this.height = height;
            this.hgDarkReference = hgDarkReference;
            this.lgDarkReference = lgDarkReference;
            this.hgFlatReference = hgFlatReference;
            this.lgFlatReference = lgFlatReference;
            this.hgFlatBase = hgFlatBase;
            this.lgFlatBase = lgFlatBase;
            this.defectMaps = defectMaps;
            this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
            this.imageProcessingService = imageProcessingService;
        }

        public HdrCalibrationApplicationResult apply(short[] hgPixels16, short[] lgPixels16) {
            if (hgPixels16 == null || lgPixels16 == null
                    || hgPixels16.length != width * height
                    || lgPixels16.length != width * height
                    || (hgDarkReference == null && hgFlatReference == null
                    && lgDarkReference == null && lgFlatReference == null)) {
                return new HdrCalibrationApplicationResult(hgPixels16, lgPixels16, details, 0, 0);
            }

            PlaneCalibrationResult hg = applyPlaneCalibration(
                    hgPixels16,
                    hgDarkReference,
                    hgFlatReference,
                    hgFlatBase);
            PlaneCalibrationResult lg = applyPlaneCalibration(
                    lgPixels16,
                    lgDarkReference,
                    lgFlatReference,
                    lgFlatBase);

            short[] hgResult = hg.pixels16;
            short[] lgResult = lg.pixels16;
            boolean defectApplied = false;
            Map<String, Object> resultDetails = new LinkedHashMap<>(details);
            resultDetails.put("calibrationApplied", true);
            resultDetails.put("hgClippedPixelCount", hg.clippedPixelCount);
            resultDetails.put("lgClippedPixelCount", lg.clippedPixelCount);
            resultDetails.put("clippedPixelCount", hg.clippedPixelCount + lg.clippedPixelCount);

            if (defectMaps != null && defectMaps.hgMap != null) {
                SpectralImageProcessingService.ProcessingResult hgMapResult =
                        imageProcessingService.processWithMultiFrameDefectMap(width, height, hgResult, defectMaps.hgMap);
                if (hgMapResult != null) {
                    hgResult = hgMapResult.getProcessedPixels16();
                    defectApplied = true;
                    resultDetails.put("hgDefectMapExecutedActions", hgMapResult.getExecutedActions());
                    resultDetails.put("hgDefectMapCorrectedBadPixelCount", hgMapResult.getCorrectedBadPixelCount());
                    resultDetails.put("hgDefectMapCorrectedRowCount", hgMapResult.getCorrectedRowCount());
                    resultDetails.put("hgDefectMapCorrectedColumnCount", hgMapResult.getCorrectedColumnCount());
                }
            }
            if (defectMaps != null && defectMaps.lgMap != null) {
                SpectralImageProcessingService.ProcessingResult lgMapResult =
                        imageProcessingService.processWithMultiFrameDefectMap(width, height, lgResult, defectMaps.lgMap);
                if (lgMapResult != null) {
                    lgResult = lgMapResult.getProcessedPixels16();
                    defectApplied = true;
                    resultDetails.put("lgDefectMapExecutedActions", lgMapResult.getExecutedActions());
                    resultDetails.put("lgDefectMapCorrectedBadPixelCount", lgMapResult.getCorrectedBadPixelCount());
                    resultDetails.put("lgDefectMapCorrectedRowCount", lgMapResult.getCorrectedRowCount());
                    resultDetails.put("lgDefectMapCorrectedColumnCount", lgMapResult.getCorrectedColumnCount());
                }
            }
            resultDetails.put("defectMapApplied", defectApplied);
            resultDetails.put("preprocessingApplied", true);
            return new HdrCalibrationApplicationResult(
                    hgResult,
                    lgResult,
                    resultDetails,
                    hg.clippedPixelCount,
                    lg.clippedPixelCount);
        }

        private PlaneCalibrationResult applyPlaneCalibration(short[] rawPixels16,
                                                            short[] darkReference,
                                                            short[] flatReference,
                                                            double flatBase) {
            short[] calibrated = new short[rawPixels16.length];
            int clippedPixelCount = 0;
            for (int index = 0; index < rawPixels16.length; index++) {
                int raw = rawPixels16[index] & SENSOR_MAX_DN;
                int dark = darkReference == null ? 0 : darkReference[index] & SENSOR_MAX_DN;
                double signal = Math.max(0.0d, raw - dark);
                if (flatReference != null) {
                    int flatDark = darkReference == null ? 0 : darkReference[index] & SENSOR_MAX_DN;
                    double flatSignal = Math.max(1.0d, (flatReference[index] & SENSOR_MAX_DN) - flatDark);
                    signal = signal * flatBase / flatSignal;
                }
                int value = (int) Math.round(signal);
                if (value > SENSOR_MAX_DN) {
                    clippedPixelCount++;
                }
                calibrated[index] = (short) Math.max(0, Math.min(SENSOR_MAX_DN, value));
            }
            return new PlaneCalibrationResult(calibrated, clippedPixelCount);
        }
    }

    @lombok.Getter
    public static final class HdrCalibrationApplicationResult {
        private final short[] hgPixels16;
        private final short[] lgPixels16;
        private final Map<String, Object> details;
        private final int hgClippedPixelCount;
        private final int lgClippedPixelCount;

        private HdrCalibrationApplicationResult(short[] hgPixels16,
                                                short[] lgPixels16,
                                                Map<String, Object> details,
                                                int hgClippedPixelCount,
                                                int lgClippedPixelCount) {
            this.hgPixels16 = hgPixels16;
            this.lgPixels16 = lgPixels16;
            this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
            this.hgClippedPixelCount = hgClippedPixelCount;
            this.lgClippedPixelCount = lgClippedPixelCount;
        }
    }

    private static final class PlaneCalibrationResult {
        private final short[] pixels16;
        private final int clippedPixelCount;

        private PlaneCalibrationResult(short[] pixels16, int clippedPixelCount) {
            this.pixels16 = pixels16;
            this.clippedPixelCount = clippedPixelCount;
        }
    }

    @lombok.Getter
    public static final class CalibrationProfile {
        private final int width;
        private final int height;
        private final short[] darkReference;
        private final short[] flatReference;
        private final double flatBase;
        private final SpectralMultiFrameQualityAnalysisService.DefectMap defectMap;
        private final Map<String, Object> details;

        private CalibrationProfile(int width,
                                   int height,
                                   short[] darkReference,
                                   short[] flatReference,
                                   double flatBase,
                                   SpectralMultiFrameQualityAnalysisService.DefectMap defectMap,
                                   Map<String, Object> details) {
            this.width = width;
            this.height = height;
            this.darkReference = darkReference;
            this.flatReference = flatReference;
            this.flatBase = flatBase;
            this.defectMap = defectMap;
            this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
        }

        public CalibrationApplicationResult apply(short[] pixels16) {
            if (pixels16 == null || pixels16.length != width * height
                    || (darkReference == null && flatReference == null)) {
                return new CalibrationApplicationResult(pixels16, details, 0);
            }
            short[] calibrated = new short[pixels16.length];
            int clippedPixelCount = 0;
            for (int index = 0; index < pixels16.length; index++) {
                int raw = pixels16[index] & SENSOR_MAX_DN;
                int dark = darkReference == null ? 0 : darkReference[index] & SENSOR_MAX_DN;
                double signal = Math.max(0.0d, raw - dark);
                if (flatReference != null) {
                    int flatDark = darkReference == null ? 0 : darkReference[index] & SENSOR_MAX_DN;
                    double flatSignal = Math.max(1.0d, (flatReference[index] & SENSOR_MAX_DN) - flatDark);
                    signal = signal * flatBase / flatSignal;
                }
                int value = (int) Math.round(signal);
                if (value > SENSOR_MAX_DN) {
                    clippedPixelCount++;
                }
                calibrated[index] = (short) Math.max(0, Math.min(SENSOR_MAX_DN, value));
            }
            Map<String, Object> resultDetails = new LinkedHashMap<>(details);
            resultDetails.put("calibrationApplied", true);
            resultDetails.put("clippedPixelCount", clippedPixelCount);
            return new CalibrationApplicationResult(calibrated, resultDetails, clippedPixelCount);
        }
    }

    @lombok.Getter
    public static final class CalibrationApplicationResult {
        private final short[] pixels16;
        private final Map<String, Object> details;
        private final int clippedPixelCount;

        private CalibrationApplicationResult(short[] pixels16,
                                             Map<String, Object> details,
                                             int clippedPixelCount) {
            this.pixels16 = pixels16;
            this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
            this.clippedPixelCount = clippedPixelCount;
        }
    }
}
