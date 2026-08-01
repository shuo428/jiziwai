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

    @Value("${spectral.storage.root:D:/GraduationProject/spectral-images}")
    private String storageRoot;

    @PostConstruct
    public void ensureCalibrationTables() {
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS t_calibration_session (" +
                        "id BIGSERIAL PRIMARY KEY," +
                        "user_id BIGINT REFERENCES t_user(id) ON DELETE SET NULL," +
                        "session_number INTEGER NOT NULL," +
                        "calibration_type VARCHAR(8) NOT NULL CHECK (calibration_type IN ('DARK','FLAT'))," +
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
                        "updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP" +
                        ")");
        // 兼容已经由前一版创建的设置表：CREATE TABLE IF NOT EXISTS 不会为旧表补列。
        jdbcTemplate.execute("ALTER TABLE t_calibration_global_setting " +
                "ADD COLUMN IF NOT EXISTS dark_calibration_id BIGINT REFERENCES t_calibration_session(id) ON DELETE SET NULL");
        jdbcTemplate.execute("ALTER TABLE t_calibration_global_setting " +
                "ADD COLUMN IF NOT EXISTS flat_calibration_id BIGINT REFERENCES t_calibration_session(id) ON DELETE SET NULL");
        jdbcTemplate.execute("ALTER TABLE t_calibration_global_setting " +
                "ADD COLUMN IF NOT EXISTS defect_map_enabled BOOLEAN NOT NULL DEFAULT FALSE");
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
        CalibrationGlobalSettingsResponse response = new CalibrationGlobalSettingsResponse();
        response.setEnabled(setting.enabled);
        response.setDarkCalibrationId(setting.darkCalibrationId);
        response.setFlatCalibrationId(setting.flatCalibrationId);
        response.setDefectMapEnabled(setting.defectMapEnabled);
        response.setCalibrationPackageReady(packageReady);
        response.setDefectMapAvailable(defectMap != null && hasDefectEntries(defectMap));
        response.setWidth(packageReady ? dark.session.getWidth() : null);
        response.setHeight(packageReady ? dark.session.getHeight() : null);
        response.setUpdatedAt(setting.updatedAt);
        response.setDarkReferenceAvailable(dark != null);
        response.setFlatReferenceAvailable(flat != null);
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
        if (Boolean.TRUE.equals(request.getEnabled())) {
            SessionReference dark = findReferenceById(userId, request.getDarkCalibrationId(), "DARK");
            SessionReference flat = findReferenceById(userId, request.getFlatCalibrationId(), "FLAT");
            if (!referencesMatch(dark, flat)) {
                throw new IllegalArgumentException("启用校准包必须选择同尺寸且 READY 的暗场、平场会话");
            }
        }
        boolean storedDefectMapEnabled = Boolean.TRUE.equals(request.getEnabled())
                && Boolean.TRUE.equals(request.getDefectMapEnabled());
        jdbcTemplate.update(
                "INSERT INTO t_calibration_global_setting(" +
                        "user_id, enabled, dark_calibration_id, flat_calibration_id, defect_map_enabled, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP) " +
                        "ON CONFLICT (user_id) DO UPDATE SET " +
                        "enabled=EXCLUDED.enabled, dark_calibration_id=EXCLUDED.dark_calibration_id, " +
                        "flat_calibration_id=EXCLUDED.flat_calibration_id, " +
                        "defect_map_enabled=EXCLUDED.defect_map_enabled, updated_at=CURRENT_TIMESTAMP",
                userId,
                request.getEnabled(),
                request.getDarkCalibrationId(),
                request.getFlatCalibrationId(),
                storedDefectMapEnabled);
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
        }

        long sessionId = createSession(userId, type, "IMAGES", sources.size(), width, height);
        Path directory = buildCalibrationDirectory(type, sessionId);
        try {
            Files.createDirectories(directory);
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
                "ORDER BY created_at DESC LIMIT 20";
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

    /**
     * 读取校准会话中的前若干张PNG预览。
     * 只返回缩小后的数量上限，避免一次把64张图片全部编码进JSON。
     */
    public List<CalibrationPreviewResponse> listPreviews(Long userId, long sessionId, int requestedLimit) {
        CalibrationSessionResponse session = get(userId, sessionId);
        if (!"READY".equals(session.getStatus()) || session.getStorageUri() == null) {
            return Collections.emptyList();
        }
        int limit = Math.max(1, Math.min(12, requestedLimit));
        int count = Math.min(session.getFrameCount(), limit);
        Path directory = resolveStorageUri(session.getStorageUri());
        List<CalibrationPreviewResponse> previews = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            Path previewFile = directory.resolve(String.format("frame-%03d.png", index)).normalize();
            if (!previewFile.startsWith(directory) || !Files.exists(previewFile)) {
                continue;
            }
            try {
                CalibrationPreviewResponse response = new CalibrationPreviewResponse();
                response.setFrameIndex(index);
                response.setImageDataUrl(encodePreviewDataUrl(previewFile));
                response.setStorageUri(toStorageUri(previewFile));
                previews.add(response);
            } catch (RuntimeException ex) {
                // 单张预览读取失败不影响其余预览。
            }
        }
        return previews;
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

    private GlobalSetting findGlobalSetting(Long userId) {
        List<GlobalSetting> settings = jdbcTemplate.query(
                "SELECT enabled, dark_calibration_id, flat_calibration_id, defect_map_enabled, updated_at " +
                        "FROM t_calibration_global_setting WHERE user_id=?",
                (resultSet, rowNum) -> new GlobalSetting(
                        resultSet.getBoolean("enabled"),
                        (Long) resultSet.getObject("dark_calibration_id"),
                        (Long) resultSet.getObject("flat_calibration_id"),
                        resultSet.getBoolean("defect_map_enabled"),
                        resultSet.getObject("updated_at", OffsetDateTime.class)),
                userId);
        return settings.isEmpty() ? new GlobalSetting(false, null, null, false, null) : settings.get(0);
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

    private boolean referencesMatch(SessionReference dark, SessionReference flat) {
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
        Path referenceFile = directory.resolve("reference.raw16le.bin");
        writeRaw(referenceFile, reference);
        summary.put("referenceStorageUri", toStorageUri(referenceFile));
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
                message + " " + result.getSummaryMessage(),
                sessionId);
        return getAnyOwnerSession(sessionId);
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

    private List<SourceImage> findUserImages(Long userId, List<Long> imageIds) {
        String placeholders = String.join(",", Collections.nCopies(imageIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.addAll(imageIds);
        return jdbcTemplate.query(
                "SELECT i.id, i.width, i.height, i.raw_storage_uri " +
                        "FROM t_spectral_image i JOIN t_spectral_capture c ON c.id=i.capture_id " +
                        "WHERE c.user_id=? AND i.id IN (" + placeholders + ") ORDER BY i.received_at ASC",
                (resultSet, rowNum) -> new SourceImage(
                        resultSet.getLong("id"),
                        resultSet.getInt("width"),
                        resultSet.getInt("height"),
                        resultSet.getString("raw_storage_uri")),
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
        if (!"DARK".equals(type) && !"FLAT".equals(type)) {
            throw new IllegalArgumentException("校准类型必须是DARK或FLAT");
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
        private final OffsetDateTime updatedAt;

        private GlobalSetting(boolean enabled,
                              Long darkCalibrationId,
                              Long flatCalibrationId,
                              boolean defectMapEnabled,
                              OffsetDateTime updatedAt) {
            this.enabled = enabled;
            this.darkCalibrationId = darkCalibrationId;
            this.flatCalibrationId = flatCalibrationId;
            this.defectMapEnabled = defectMapEnabled;
            this.updatedAt = updatedAt;
        }
    }

    private static final class SourceImage {
        private final long id;
        private final int width;
        private final int height;
        private final String rawStorageUri;

        private SourceImage(long id, int width, int height, String rawStorageUri) {
            this.id = id;
            this.width = width;
            this.height = height;
            this.rawStorageUri = rawStorageUri;
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
