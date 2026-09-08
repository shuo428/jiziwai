package springbootjni.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import springbootjni.dto.jni.GeometryCorrectionRequest;
import springbootjni.dto.jni.GeometryCorrectionResponse;
import springbootjni.dto.jni.GeometryProfileRequest;
import springbootjni.dto.jni.GeometryProfileResponse;
import springbootjni.dto.jni.SpectrumExtractionRequest;
import springbootjni.dto.jni.SpectrumExtractionResponse;

import javax.annotation.PostConstruct;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 光谱二维图像几何校正服务。
 *
 * <p>这个模块解决的是“二维谱图有没有摆正”的问题，不做波长 nm 标定，也不改变光强的
 * 定量含义。当前实现分为两个可先落地的版本：</p>
 * <ol>
 *     <li>第一版：配置 ROI、波长方向、旋转和翻转，把采集方向不确定的问题先收口。</li>
 *     <li>第二版：在 ROI 内用整数像素互相关估计每一行/列相对平均谱形的偏移，
 *     对轻微倾斜做平移对齐。</li>
 * </ol>
 *
 * <p>第三版会在真实 CMOS/FPGA 图像和标定光源到来后继续做：谱线中心提取、smile/keystone
 * 多项式模型和亚像素重采样。这个 TODO 写进代码，是为了避免后续忘记“真实数据驱动”这件事。</p>
 */
@Service
@RequiredArgsConstructor
public class SpectralGeometryCorrectionService {
    public static final String ALGORITHM_VERSION = "spectral-geometry-correction-v1";

    private static final int SENSOR_MAX_DN = 4095;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SpectralCalibrationService calibrationService;
    private final SpectralImageProcessingService imageProcessingService;

    @Value("${spectral.storage.root:D:/GraduationProject/spectral-images}")
    private String storageRoot;

    @PostConstruct
    public void ensureGeometryTables() {
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS t_spectral_geometry_profile (" +
                        "id BIGSERIAL PRIMARY KEY, " +
                        "user_id BIGINT REFERENCES t_user(id) ON DELETE CASCADE, " +
                        "profile_name VARCHAR(96) NOT NULL, " +
                        "mode_type VARCHAR(16) NOT NULL CHECK (mode_type IN ('NORMAL','HDR')), " +
                        "image_width INTEGER, " +
                        "image_height INTEGER, " +
                        "source_mode VARCHAR(32) NOT NULL DEFAULT 'AUTO' CHECK (source_mode IN ('AUTO','ORIGINAL','CALIBRATED','PROCESSED')), " +
                        "dispersion_axis VARCHAR(8) NOT NULL DEFAULT 'AUTO' CHECK (dispersion_axis IN ('AUTO','X','Y')), " +
                        "roi JSONB NOT NULL DEFAULT '{}'::JSONB, " +
                        "rotate_degrees INTEGER NOT NULL DEFAULT 0 CHECK (rotate_degrees IN (0,90,180,270)), " +
                        "flip_x BOOLEAN NOT NULL DEFAULT FALSE, " +
                        "flip_y BOOLEAN NOT NULL DEFAULT FALSE, " +
                        "tilt_correction_enabled BOOLEAN NOT NULL DEFAULT TRUE, " +
                        "max_shift_pixels INTEGER, " +
                        "enabled BOOLEAN NOT NULL DEFAULT FALSE, " +
                        "algorithm_version VARCHAR(64) NOT NULL, " +
                        "details JSONB NOT NULL DEFAULT '{}'::JSONB, " +
                        "created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                        "updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP" +
                        ")");
        jdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_geometry_profile_user_mode " +
                        "ON t_spectral_geometry_profile(user_id, mode_type, enabled, updated_at DESC)");
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS t_image_geometry_correction (" +
                        "id BIGSERIAL PRIMARY KEY, " +
                        "image_id BIGINT NOT NULL REFERENCES t_spectral_image(id) ON DELETE CASCADE, " +
                        "capture_id BIGINT NOT NULL REFERENCES t_spectral_capture(id) ON DELETE CASCADE, " +
                        "user_id BIGINT REFERENCES t_user(id) ON DELETE CASCADE, " +
                        "profile_id BIGINT REFERENCES t_spectral_geometry_profile(id) ON DELETE SET NULL, " +
                        "mode_type VARCHAR(16) NOT NULL CHECK (mode_type IN ('NORMAL','HDR')), " +
                        "source_mode VARCHAR(32) NOT NULL CHECK (source_mode IN ('ORIGINAL','CALIBRATED','PROCESSED')), " +
                        "source_quality_status VARCHAR(16) NOT NULL, " +
                        "input_raw_storage_uri TEXT NOT NULL, " +
                        "output_raw_storage_uri TEXT NOT NULL, " +
                        "output_preview_storage_uri TEXT, " +
                        "width INTEGER NOT NULL, " +
                        "height INTEGER NOT NULL, " +
                        "dispersion_axis VARCHAR(8) NOT NULL CHECK (dispersion_axis IN ('X','Y')), " +
                        "roi JSONB NOT NULL, " +
                        "transform_details JSONB NOT NULL DEFAULT '{}'::JSONB, " +
                        "algorithm_version VARCHAR(64) NOT NULL, " +
                        "summary_message TEXT, " +
                        "created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP" +
                        ")");
        jdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_image_geometry_user_image " +
                        "ON t_image_geometry_correction(user_id, image_id, created_at DESC)");
        jdbcTemplate.execute(
                "CREATE UNIQUE INDEX IF NOT EXISTS ux_image_geometry_image " +
                        "ON t_image_geometry_correction(image_id)");
    }

    @Transactional(readOnly = true)
    public List<GeometryProfileResponse> listProfiles(Long userId, String modeType) {
        String normalizedMode = normalizeModeType(modeType, null);
        String sql = "SELECT id, profile_name, mode_type, image_width, image_height, source_mode, " +
                "dispersion_axis, roi::text AS roi_json, rotate_degrees, flip_x, flip_y, " +
                "tilt_correction_enabled, max_shift_pixels, enabled, algorithm_version, " +
                "details::text AS details_json, created_at, updated_at " +
                "FROM t_spectral_geometry_profile WHERE user_id=? ";
        List<Object> args = new ArrayList<>();
        args.add(userId);
        if (normalizedMode != null) {
            sql += "AND mode_type=? ";
            args.add(normalizedMode);
        }
        sql += "ORDER BY enabled DESC, updated_at DESC, id DESC";
        return jdbcTemplate.query(sql, (rs, rowNum) -> mapProfile(rs), args.toArray());
    }

    @Transactional(readOnly = true)
    public GeometryProfileResponse getEnabledProfile(Long userId, String modeType) {
        GeometryProfileRow row = findEnabledProfile(userId, normalizeModeType(modeType, "NORMAL"));
        return row == null ? null : row.toResponse();
    }

    @Transactional
    public GeometryProfileResponse saveProfile(Long userId, GeometryProfileRequest request) {
        GeometryProfileRow row = normalizeProfileRequest(userId, request, null, null);
        if (Boolean.TRUE.equals(row.enabled)) {
            disableOtherProfiles(userId, row.modeType, row.id);
        }
        if (row.id == null) {
            Long id = jdbcTemplate.queryForObject(
                    "INSERT INTO t_spectral_geometry_profile " +
                            "(user_id, profile_name, mode_type, image_width, image_height, source_mode, " +
                            "dispersion_axis, roi, rotate_degrees, flip_x, flip_y, tilt_correction_enabled, " +
                            "max_shift_pixels, enabled, algorithm_version, details) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb)) " +
                            "RETURNING id",
                    Long.class,
                    userId,
                    row.profileName,
                    row.modeType,
                    row.imageWidth,
                    row.imageHeight,
                    row.sourceMode,
                    row.dispersionAxis,
                    toJson(row.roi.toMap()),
                    row.rotateDegrees,
                    row.flipX,
                    row.flipY,
                    row.tiltCorrectionEnabled,
                    row.maxShiftPixels,
                    row.enabled,
                    ALGORITHM_VERSION,
                    toJson(row.details));
            row.id = id;
        } else {
            Integer updated = jdbcTemplate.update(
                    "UPDATE t_spectral_geometry_profile SET profile_name=?, mode_type=?, image_width=?, image_height=?, " +
                            "source_mode=?, dispersion_axis=?, roi=CAST(? AS jsonb), rotate_degrees=?, flip_x=?, flip_y=?, " +
                            "tilt_correction_enabled=?, max_shift_pixels=?, enabled=?, algorithm_version=?, " +
                            "details=CAST(? AS jsonb), updated_at=CURRENT_TIMESTAMP " +
                            "WHERE id=? AND user_id=?",
                    row.profileName,
                    row.modeType,
                    row.imageWidth,
                    row.imageHeight,
                    row.sourceMode,
                    row.dispersionAxis,
                    toJson(row.roi.toMap()),
                    row.rotateDegrees,
                    row.flipX,
                    row.flipY,
                    row.tiltCorrectionEnabled,
                    row.maxShiftPixels,
                    row.enabled,
                    ALGORITHM_VERSION,
                    toJson(row.details),
                    row.id,
                    userId);
            if (updated == null || updated == 0) {
                throw new IllegalArgumentException("几何校正配置不存在或不属于当前用户");
            }
        }
        if (Boolean.TRUE.equals(row.enabled)) {
            disableOtherProfiles(userId, row.modeType, row.id);
        }
        return loadProfile(userId, row.id).toResponse();
    }

    @Transactional
    public boolean deleteProfile(Long userId, long profileId) {
        int deleted = jdbcTemplate.update(
                "DELETE FROM t_spectral_geometry_profile WHERE id=? AND user_id=?",
                profileId,
                userId);
        return deleted > 0;
    }

    @Transactional
    public GeometryCorrectionResponse analyze(Long userId,
                                              long imageId,
                                              GeometryCorrectionRequest request) {
        CorrectionBuild build = buildCorrection(userId, imageId, request);
        return toResponse(null, build, null, null, null, OffsetDateTime.now(ZoneOffset.UTC));
    }

    @Transactional
    public GeometryCorrectionResponse correct(Long userId,
                                              long imageId,
                                              GeometryCorrectionRequest request) {
        CorrectionBuild build = buildCorrection(userId, imageId, request);
        Long profileId = build.plan.profileId;
        if (Boolean.TRUE.equals(request == null ? null : request.getSaveAsEnabledProfile())) {
            GeometryProfileRow row = build.plan.toProfileRow(userId, build.source.width, build.source.height);
            row.enabled = true;
            GeometryProfileResponse saved = saveProfile(userId, row.toRequest());
            profileId = saved.getId();
            build.plan.profileId = profileId;
        }

        Path outputDirectory = resolveStorageUri(build.source.rawStorageUri).getParent().resolve("geometry");
        try {
            Files.createDirectories(outputDirectory);
            Path outputRawFile = outputDirectory.resolve("geometry-corrected.raw16le.bin");
            Path outputPreviewFile = outputDirectory.resolve("geometry-preview.png");
            Files.write(outputRawFile, toLittleEndianRawBytes(build.corrected.pixels16));
            writePreviewPng(build.corrected.width, build.corrected.height, toPreviewBytes(build.corrected.pixels16), outputPreviewFile);
            String outputRawUri = toStorageUri(outputRawFile);
            String outputPreviewUri = toStorageUri(outputPreviewFile);

            SavedCorrection saved = jdbcTemplate.queryForObject(
                    "INSERT INTO t_image_geometry_correction " +
                            "(image_id, capture_id, user_id, profile_id, mode_type, source_mode, source_quality_status, " +
                            "input_raw_storage_uri, output_raw_storage_uri, output_preview_storage_uri, width, height, " +
                            "dispersion_axis, roi, transform_details, algorithm_version, summary_message) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?) " +
                            "ON CONFLICT (image_id) DO UPDATE SET " +
                            "capture_id=EXCLUDED.capture_id, user_id=EXCLUDED.user_id, profile_id=EXCLUDED.profile_id, " +
                            "mode_type=EXCLUDED.mode_type, source_mode=EXCLUDED.source_mode, " +
                            "source_quality_status=EXCLUDED.source_quality_status, input_raw_storage_uri=EXCLUDED.input_raw_storage_uri, " +
                            "output_raw_storage_uri=EXCLUDED.output_raw_storage_uri, " +
                            "output_preview_storage_uri=EXCLUDED.output_preview_storage_uri, width=EXCLUDED.width, height=EXCLUDED.height, " +
                            "dispersion_axis=EXCLUDED.dispersion_axis, roi=EXCLUDED.roi, " +
                            "transform_details=EXCLUDED.transform_details, algorithm_version=EXCLUDED.algorithm_version, " +
                            "summary_message=EXCLUDED.summary_message, created_at=CURRENT_TIMESTAMP " +
                            "RETURNING id, created_at",
                    (rs, rowNum) -> new SavedCorrection(rs.getLong("id"), rs.getObject("created_at", OffsetDateTime.class)),
                    build.source.imageId,
                    build.source.captureId,
                    userId,
                    profileId,
                    build.source.modeType,
                    build.selectedSource.sourceMode,
                    build.selectedSource.qualityStatus,
                    build.selectedSource.rawStorageUri,
                    outputRawUri,
                    outputPreviewUri,
                    build.corrected.width,
                    build.corrected.height,
                    build.dispersionAxis,
                    toJson(build.roi.toMap()),
                    toJson(build.details),
                    ALGORITHM_VERSION,
                    build.summaryMessage);
            return toResponse(saved.id, build, outputRawUri, outputPreviewUri, encodePreviewDataUrl(outputPreviewFile), saved.createdAt);
        } catch (IOException ex) {
            throw new IllegalStateException("保存几何校正图像失败: " + ex.getMessage(), ex);
        }
    }

    @Transactional(readOnly = true)
    public GeometryCorrectionResponse getLatest(Long userId, long imageId) {
        List<GeometryCorrectionResponse> rows = jdbcTemplate.query(
                "SELECT gc.id, gc.image_id, gc.capture_id, gc.profile_id, gc.mode_type, gc.source_mode, " +
                        "gc.source_quality_status, gc.width, gc.height, gc.dispersion_axis, gc.roi::text AS roi_json, " +
                        "gc.output_raw_storage_uri, gc.output_preview_storage_uri, gc.transform_details::text AS details_json, " +
                        "gc.algorithm_version, gc.summary_message, gc.created_at " +
                        "FROM t_image_geometry_correction gc " +
                        "JOIN t_spectral_capture c ON c.id=gc.capture_id " +
                        "WHERE gc.image_id=? AND c.user_id=? " +
                        "ORDER BY gc.created_at DESC LIMIT 1",
                (rs, rowNum) -> mapCorrection(rs),
                imageId,
                userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Transactional(readOnly = true)
    public CorrectedSource loadLatestCorrectedSource(Long userId, long imageId) {
        List<CorrectedSource> rows = jdbcTemplate.query(
                "SELECT gc.id, gc.image_id, gc.capture_id, gc.profile_id, gc.source_quality_status, " +
                        "gc.output_raw_storage_uri, gc.width, gc.height, gc.dispersion_axis, " +
                        "gc.roi::text AS roi_json, gc.transform_details::text AS details_json, gc.summary_message " +
                        "FROM t_image_geometry_correction gc " +
                        "JOIN t_spectral_capture c ON c.id=gc.capture_id " +
                        "WHERE gc.image_id=? AND c.user_id=? " +
                        "ORDER BY gc.created_at DESC LIMIT 1",
                (rs, rowNum) -> new CorrectedSource(
                        rs.getLong("id"),
                        rs.getLong("image_id"),
                        rs.getLong("capture_id"),
                        (Long) rs.getObject("profile_id"),
                        rs.getString("source_quality_status"),
                        rs.getString("output_raw_storage_uri"),
                        rs.getInt("width"),
                        rs.getInt("height"),
                        rs.getString("dispersion_axis"),
                        parseJsonMap(rs.getString("roi_json")),
                        parseJsonMap(rs.getString("details_json")),
                        rs.getString("summary_message")),
                imageId,
                userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private CorrectionBuild buildCorrection(Long userId,
                                            long imageId,
                                            GeometryCorrectionRequest request) {
        ImageSource source = loadImageSource(userId, imageId);
        CorrectionPlan plan = resolvePlan(userId, source, request);
        SelectedSource selectedSource = selectSource(source, plan.sourceMode);
        short[] pixels16 = readRaw16Le(resolveStorageUri(selectedSource.rawStorageUri), source.width * source.height);
        Map<String, Object> preprocessingDetails = new LinkedHashMap<>();
        if (selectedSource.applyCapturedCalibration) {
            SpectralCalibrationService.CalibrationProfile calibrationProfile =
                    calibrationService.loadCapturedProfile(userId, source.width, source.height, source.qualityDetails);
            SpectralCalibrationService.CalibrationApplicationResult calibration = calibrationProfile.apply(pixels16);
            pixels16 = calibration.getPixels16();
            preprocessingDetails.putAll(calibration.getDetails());
            if (calibrationProfile.getDefectMap() != null) {
                SpectralImageProcessingService.ProcessingResult mapResult =
                        imageProcessingService.processWithMultiFrameDefectMap(
                                source.width,
                                source.height,
                                pixels16,
                                calibrationProfile.getDefectMap());
                if (mapResult != null) {
                    pixels16 = mapResult.getProcessedPixels16();
                    preprocessingDetails.put("defectMapApplied", true);
                    preprocessingDetails.put("defectMapExecutedActions", mapResult.getExecutedActions());
                }
            }
        } else {
            preprocessingDetails.putAll(selectedSource.preprocessingDetails);
            preprocessingDetails.put("preprocessingApplied", selectedSource.preprocessingApplied);
        }

        ImageMatrix oriented = applyOrientation(pixels16, source.width, source.height, plan.rotateDegrees, plan.flipX, plan.flipY);
        Roi roi = normalizeRoi(plan.roiInput, oriented.width, oriented.height);
        AxisDetection axisDetection = detectAxis(oriented.pixels16, oriented.width, roi);
        String dispersionAxis = "AUTO".equals(plan.dispersionAxis) ? axisDetection.axis : plan.dispersionAxis;
        int maxShift = normalizeMaxShift(plan.maxShiftPixels, roi, dispersionAxis);
        GeometryComputation computation = applyTiltCorrection(
                oriented,
                roi,
                dispersionAxis,
                plan.tiltCorrectionEnabled,
                maxShift);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("algorithmVersion", ALGORITHM_VERSION);
        details.put("modeType", source.modeType);
        details.put("sourceMode", selectedSource.sourceMode);
        details.put("sourceRawStorageUri", selectedSource.rawStorageUri);
        details.put("preprocessing", preprocessingDetails);
        details.put("orientation", orientationMap(plan, source, oriented));
        details.put("roi", roi.toMap());
        details.put("axisDetection", axisDetection.toMap());
        details.put("selectedDispersionAxis", dispersionAxis);
        details.put("tiltCorrection", computation.toMap());

        String summary = "已完成二维几何校正：来源=" + selectedSource.sourceMode +
                "，输出尺寸=" + computation.matrix.width + "x" + computation.matrix.height +
                "，波长方向=" + dispersionAxis +
                "，ROI=" + roi.width() + "x" + roi.height() +
                "，偏移范围=" + computation.shiftMin + " 到 " + computation.shiftMax + " px。";
        return new CorrectionBuild(source, selectedSource, plan, computation.matrix, roi, dispersionAxis,
                axisDetection.confidence, computation, details, summary);
    }

    private ImageSource loadImageSource(Long userId, long imageId) {
        List<ImageSource> rows = jdbcTemplate.query(
                "SELECT i.id AS image_id, i.capture_id, c.capture_scene, i.width, i.height, i.raw_storage_uri, " +
                        "i.calibrated_raw_storage_uri, qa.quality_status, qa.details::text AS quality_details_json, " +
                        "al.details::text AS processing_details_json " +
                        "FROM t_spectral_image i " +
                        "JOIN t_spectral_capture c ON c.id=i.capture_id " +
                        "LEFT JOIN t_image_quality_analysis qa ON qa.image_id=i.id " +
                        "LEFT JOIN LATERAL (" +
                        "    SELECT details FROM t_image_action_log " +
                        "    WHERE image_id=i.id AND action_type='CORRECT' AND action_status='SUCCESS' " +
                        "      AND details->>'processingStatus'='PROCESSED' " +
                        "    ORDER BY created_at DESC LIMIT 1" +
                        ") al ON TRUE " +
                        "WHERE i.id=? AND c.user_id=? AND c.capture_scene IN ('NORMAL','HDR')",
                (rs, rowNum) -> {
                    ImageSource source = new ImageSource();
                    source.imageId = rs.getLong("image_id");
                    source.captureId = rs.getLong("capture_id");
                    source.modeType = normalizeModeType(rs.getString("capture_scene"), "NORMAL");
                    source.width = rs.getInt("width");
                    source.height = rs.getInt("height");
                    source.rawStorageUri = rs.getString("raw_storage_uri");
                    source.calibratedRawStorageUri = rs.getString("calibrated_raw_storage_uri");
                    source.originalQualityStatus = rs.getString("quality_status");
                    source.qualityDetails = parseJsonMap(rs.getString("quality_details_json"));
                    source.processingDetails = parseJsonMap(rs.getString("processing_details_json"));
                    return source;
                },
                imageId,
                userId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("图像不存在或不属于当前用户");
        }
        return rows.get(0);
    }

    @SuppressWarnings("unchecked")
    private SelectedSource selectSource(ImageSource source, String requestedMode) {
        Map<String, Object> processedQuality = asMap(source.processingDetails.get("processedQuality"));
        String processedQualityStatus = asString(processedQuality.get("qualityStatus"));
        String processedRawUri = asString(source.processingDetails.get("processedRawStorageUri"));
        Map<String, Object> processedQualityDetails = asMap(processedQuality.get("details"));
        Map<String, Object> capturedCalibration = asMap(source.qualityDetails.get("calibration"));
        Map<String, Object> processedCalibration = asMap(source.processingDetails.get("calibration"));
        if (processedCalibration.isEmpty()) {
            processedCalibration = asMap(processedQualityDetails.get("calibration"));
        }
        boolean processedPreprocessingApplied = Boolean.TRUE.equals(source.processingDetails.get("preprocessingApplied"))
                || Boolean.TRUE.equals(processedCalibration.get("calibrationApplied"));
        boolean originalPass = "PASS".equals(source.originalQualityStatus);
        boolean processedPass = "PASS".equals(processedQualityStatus) && !processedRawUri.isEmpty();
        boolean calibratedRawAvailable = source.calibratedRawStorageUri != null
                && !source.calibratedRawStorageUri.trim().isEmpty();
        boolean hasCapturedCalibration = Boolean.TRUE.equals(capturedCalibration.get("calibrationApplied"))
                || Boolean.TRUE.equals(capturedCalibration.get("defectMapApplied"));
        boolean calibratedPass = originalPass && (calibratedRawAvailable || hasCapturedCalibration);

        if ("PROCESSED".equals(requestedMode)) {
            if (!processedPass) {
                throw new IllegalStateException("当前图片没有可用于几何校正的处理后PASS结果");
            }
            return new SelectedSource("PROCESSED", processedQualityStatus, processedRawUri,
                    processedPreprocessingApplied, false, processedCalibration);
        }
        if ("CALIBRATED".equals(requestedMode)) {
            if (!calibratedPass) {
                throw new IllegalStateException("当前图片没有可用于几何校正的校准后PASS结果");
            }
            if (calibratedRawAvailable) {
                return new SelectedSource("CALIBRATED", source.originalQualityStatus, source.calibratedRawStorageUri,
                        true, false, capturedCalibration);
            }
            return new SelectedSource("CALIBRATED", source.originalQualityStatus, source.rawStorageUri,
                    false, true, capturedCalibration);
        }
        if ("ORIGINAL".equals(requestedMode)) {
            if (!originalPass) {
                throw new IllegalStateException("原图质量不是PASS，不能直接做几何校正");
            }
            return new SelectedSource("ORIGINAL", source.originalQualityStatus, source.rawStorageUri,
                    false, false, Collections.singletonMap("preprocessingApplied", false));
        }
        if (processedPass) {
            return new SelectedSource("PROCESSED", processedQualityStatus, processedRawUri,
                    processedPreprocessingApplied, false, processedCalibration);
        }
        if (calibratedPass) {
            if (calibratedRawAvailable) {
                return new SelectedSource("CALIBRATED", source.originalQualityStatus, source.calibratedRawStorageUri,
                        true, false, capturedCalibration);
            }
            return new SelectedSource("CALIBRATED", source.originalQualityStatus, source.rawStorageUri,
                    false, true, capturedCalibration);
        }
        if (originalPass) {
            return new SelectedSource("ORIGINAL", source.originalQualityStatus, source.rawStorageUri,
                    false, false, Collections.singletonMap("preprocessingApplied", false));
        }
        throw new IllegalStateException("当前图片原图、校准后图和处理后结果均不是PASS，不能做几何校正");
    }

    private CorrectionPlan resolvePlan(Long userId, ImageSource source, GeometryCorrectionRequest request) {
        GeometryProfileRow profile = null;
        if (request != null && request.getProfileId() != null) {
            profile = loadProfile(userId, request.getProfileId());
        } else if (request == null || request.getUseEnabledProfile() == null || request.getUseEnabledProfile()) {
            profile = findEnabledProfile(userId, source.modeType);
        }
        CorrectionPlan plan = CorrectionPlan.defaults(source.modeType);
        if (profile != null) {
            if (profile.imageWidth != null && profile.imageHeight != null
                    && (profile.imageWidth != source.width || profile.imageHeight != source.height)) {
                throw new IllegalStateException("当前几何校正配置尺寸为 "
                        + profile.imageWidth + "x" + profile.imageHeight
                        + "，与图像尺寸 " + source.width + "x" + source.height + " 不一致");
            }
            plan = CorrectionPlan.fromProfile(profile);
        }
        if (request != null) {
            if (request.getModeType() != null) {
                plan.modeType = normalizeModeType(request.getModeType(), source.modeType);
            }
            if (request.getSourceMode() != null) {
                plan.sourceMode = normalizeSourceMode(request.getSourceMode(), false);
            }
            if (request.getDispersionAxis() != null) {
                plan.dispersionAxis = normalizeAxis(request.getDispersionAxis());
            }
            if (request.getRotateDegrees() != null) {
                plan.rotateDegrees = normalizeRotate(request.getRotateDegrees());
            }
            if (request.getFlipX() != null) {
                plan.flipX = request.getFlipX();
            }
            if (request.getFlipY() != null) {
                plan.flipY = request.getFlipY();
            }
            if (request.getTiltCorrectionEnabled() != null) {
                plan.tiltCorrectionEnabled = request.getTiltCorrectionEnabled();
            }
            if (request.getMaxShiftPixels() != null) {
                plan.maxShiftPixels = Math.max(0, request.getMaxShiftPixels());
            }
            if (request.getRoi() != null) {
                plan.roiInput = request.getRoi();
            }
            if (request.getProfileName() != null && !request.getProfileName().trim().isEmpty()) {
                plan.profileName = request.getProfileName().trim();
            }
        }
        if (!source.modeType.equals(plan.modeType)) {
            throw new IllegalStateException("当前图像属于" + source.modeType + "模式，不能套用" + plan.modeType + "模式几何配置");
        }
        return plan;
    }

    private GeometryProfileRow normalizeProfileRequest(Long userId,
                                                       GeometryProfileRequest request,
                                                       Integer width,
                                                       Integer height) {
        if (request == null) {
            throw new IllegalArgumentException("几何校正配置不能为空");
        }
        GeometryProfileRow row = new GeometryProfileRow();
        row.id = request.getId();
        row.profileName = request.getProfileName() == null || request.getProfileName().trim().isEmpty()
                ? "默认几何校正配置"
                : request.getProfileName().trim();
        row.modeType = normalizeModeType(request.getModeType(), "NORMAL");
        row.imageWidth = width;
        row.imageHeight = height;
        row.sourceMode = normalizeSourceMode(request.getSourceMode(), true);
        row.dispersionAxis = normalizeAxis(request.getDispersionAxis());
        row.rotateDegrees = normalizeRotate(request.getRotateDegrees());
        row.flipX = Boolean.TRUE.equals(request.getFlipX());
        row.flipY = Boolean.TRUE.equals(request.getFlipY());
        row.tiltCorrectionEnabled = request.getTiltCorrectionEnabled() == null || request.getTiltCorrectionEnabled();
        row.maxShiftPixels = request.getMaxShiftPixels() == null ? null : Math.max(0, request.getMaxShiftPixels());
        row.roi = normalizeNullableRoi(request.getRoi());
        row.enabled = Boolean.TRUE.equals(request.getEnabled());
        row.details = request.getDetails() == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<>(request.getDetails());
        return row;
    }

    private GeometryProfileRow loadProfile(Long userId, Long profileId) {
        if (profileId == null) {
            return null;
        }
        List<GeometryProfileRow> rows = jdbcTemplate.query(
                "SELECT id, profile_name, mode_type, image_width, image_height, source_mode, dispersion_axis, " +
                        "roi::text AS roi_json, rotate_degrees, flip_x, flip_y, tilt_correction_enabled, " +
                        "max_shift_pixels, enabled, algorithm_version, details::text AS details_json, created_at, updated_at " +
                        "FROM t_spectral_geometry_profile WHERE id=? AND user_id=?",
                (rs, rowNum) -> mapProfileRow(rs),
                profileId,
                userId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("几何校正配置不存在或不属于当前用户");
        }
        return rows.get(0);
    }

    private GeometryProfileRow findEnabledProfile(Long userId, String modeType) {
        List<GeometryProfileRow> rows = jdbcTemplate.query(
                "SELECT id, profile_name, mode_type, image_width, image_height, source_mode, dispersion_axis, " +
                        "roi::text AS roi_json, rotate_degrees, flip_x, flip_y, tilt_correction_enabled, " +
                        "max_shift_pixels, enabled, algorithm_version, details::text AS details_json, created_at, updated_at " +
                        "FROM t_spectral_geometry_profile WHERE user_id=? AND mode_type=? AND enabled=TRUE " +
                        "ORDER BY updated_at DESC, id DESC LIMIT 1",
                (rs, rowNum) -> mapProfileRow(rs),
                userId,
                modeType);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void disableOtherProfiles(Long userId, String modeType, Long keepId) {
        if (keepId == null) {
            jdbcTemplate.update(
                    "UPDATE t_spectral_geometry_profile SET enabled=FALSE, updated_at=CURRENT_TIMESTAMP " +
                            "WHERE user_id=? AND mode_type=?",
                    userId,
                    modeType);
            return;
        }
        jdbcTemplate.update(
                "UPDATE t_spectral_geometry_profile SET enabled=FALSE, updated_at=CURRENT_TIMESTAMP " +
                        "WHERE user_id=? AND mode_type=? AND id<>?",
                userId,
                modeType,
                keepId);
    }

    private GeometryProfileResponse mapProfile(ResultSet rs) throws SQLException {
        return mapProfileRow(rs).toResponse();
    }

    private GeometryProfileRow mapProfileRow(ResultSet rs) throws SQLException {
        GeometryProfileRow row = new GeometryProfileRow();
        row.id = rs.getLong("id");
        row.profileName = rs.getString("profile_name");
        row.modeType = rs.getString("mode_type");
        row.imageWidth = (Integer) rs.getObject("image_width");
        row.imageHeight = (Integer) rs.getObject("image_height");
        row.sourceMode = rs.getString("source_mode");
        row.dispersionAxis = rs.getString("dispersion_axis");
        row.roi = roiFromMap(parseJsonMap(rs.getString("roi_json")));
        row.rotateDegrees = rs.getInt("rotate_degrees");
        row.flipX = rs.getBoolean("flip_x");
        row.flipY = rs.getBoolean("flip_y");
        row.tiltCorrectionEnabled = rs.getBoolean("tilt_correction_enabled");
        row.maxShiftPixels = (Integer) rs.getObject("max_shift_pixels");
        row.enabled = rs.getBoolean("enabled");
        row.algorithmVersion = rs.getString("algorithm_version");
        row.details = parseJsonMap(rs.getString("details_json"));
        row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
        row.updatedAt = rs.getObject("updated_at", OffsetDateTime.class);
        return row;
    }

    private GeometryCorrectionResponse mapCorrection(ResultSet rs) throws SQLException {
        Map<String, Object> details = parseJsonMap(rs.getString("details_json"));
        GeometryCorrectionResponse response = new GeometryCorrectionResponse();
        response.setId(rs.getLong("id"));
        response.setImageId(rs.getLong("image_id"));
        response.setCaptureId(rs.getLong("capture_id"));
        response.setProfileId((Long) rs.getObject("profile_id"));
        response.setModeType(rs.getString("mode_type"));
        response.setSourceMode(rs.getString("source_mode"));
        response.setSourceQualityStatus(rs.getString("source_quality_status"));
        response.setWidth(rs.getInt("width"));
        response.setHeight(rs.getInt("height"));
        response.setDispersionAxis(rs.getString("dispersion_axis"));
        response.setRoi(roiFromMap(parseJsonMap(rs.getString("roi_json"))).toResponseRoi());
        response.setOutputRawStorageUri(rs.getString("output_raw_storage_uri"));
        response.setOutputPreviewStorageUri(rs.getString("output_preview_storage_uri"));
        response.setImageDataUrl(encodePreviewDataUrl(resolveStorageUri(response.getOutputPreviewStorageUri())));
        response.setAlgorithmVersion(rs.getString("algorithm_version"));
        response.setSummaryMessage(rs.getString("summary_message"));
        response.setDetails(details);
        response.setCreatedAt(rs.getObject("created_at", OffsetDateTime.class));
        fillMetricFields(response, details);
        return response;
    }

    private GeometryCorrectionResponse toResponse(Long id,
                                                  CorrectionBuild build,
                                                  String outputRawUri,
                                                  String outputPreviewUri,
                                                  String imageDataUrl,
                                                  OffsetDateTime createdAt) {
        GeometryCorrectionResponse response = new GeometryCorrectionResponse();
        response.setId(id);
        response.setImageId(build.source.imageId);
        response.setCaptureId(build.source.captureId);
        response.setProfileId(build.plan.profileId);
        response.setModeType(build.source.modeType);
        response.setSourceMode(build.selectedSource.sourceMode);
        response.setSourceQualityStatus(build.selectedSource.qualityStatus);
        response.setDispersionAxis(build.dispersionAxis);
        response.setRoi(build.roi.toResponseRoi());
        response.setOrientationApplied(build.plan.rotateDegrees != 0 || build.plan.flipX || build.plan.flipY);
        response.setRotateDegrees(build.plan.rotateDegrees);
        response.setFlipX(build.plan.flipX);
        response.setFlipY(build.plan.flipY);
        response.setTiltCorrectionApplied(build.computation.applied);
        response.setMaxShiftPixels(build.computation.maxShiftPixels);
        response.setShiftMin(build.computation.shiftMin);
        response.setShiftMax(build.computation.shiftMax);
        response.setShiftMeanAbs(build.computation.shiftMeanAbs);
        response.setAxisConfidence(build.axisConfidence);
        response.setWidth(build.corrected.width);
        response.setHeight(build.corrected.height);
        response.setOutputRawStorageUri(outputRawUri);
        response.setOutputPreviewStorageUri(outputPreviewUri);
        response.setImageDataUrl(imageDataUrl);
        response.setAlgorithmVersion(ALGORITHM_VERSION);
        response.setSummaryMessage(build.summaryMessage);
        response.setDetails(build.details);
        response.setCreatedAt(createdAt == null ? OffsetDateTime.now(ZoneOffset.UTC) : createdAt);
        return response;
    }

    @SuppressWarnings("unchecked")
    private void fillMetricFields(GeometryCorrectionResponse response, Map<String, Object> details) {
        response.setRotateDegrees(asInteger(asMap(details.get("orientation")).get("rotateDegrees"), 0));
        response.setFlipX(Boolean.TRUE.equals(asMap(details.get("orientation")).get("flipX")));
        response.setFlipY(Boolean.TRUE.equals(asMap(details.get("orientation")).get("flipY")));
        response.setOrientationApplied(Boolean.TRUE.equals(asMap(details.get("orientation")).get("orientationApplied")));
        Map<String, Object> tilt = asMap(details.get("tiltCorrection"));
        response.setTiltCorrectionApplied(Boolean.TRUE.equals(tilt.get("applied")));
        response.setMaxShiftPixels(asInteger(tilt.get("maxShiftPixels"), 0));
        response.setShiftMin(asInteger(tilt.get("shiftMin"), 0));
        response.setShiftMax(asInteger(tilt.get("shiftMax"), 0));
        response.setShiftMeanAbs(asDouble(tilt.get("shiftMeanAbs"), 0.0d));
        response.setAxisConfidence(asDouble(asMap(details.get("axisDetection")).get("confidence"), 0.0d));
    }

    private ImageMatrix applyOrientation(short[] pixels16,
                                         int width,
                                         int height,
                                         int rotateDegrees,
                                         boolean flipX,
                                         boolean flipY) {
        int rotatedWidth = (rotateDegrees == 90 || rotateDegrees == 270) ? height : width;
        int rotatedHeight = (rotateDegrees == 90 || rotateDegrees == 270) ? width : height;
        short[] output = new short[rotatedWidth * rotatedHeight];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int xf = flipX ? width - 1 - x : x;
                int yf = flipY ? height - 1 - y : y;
                int nx;
                int ny;
                if (rotateDegrees == 90) {
                    nx = height - 1 - yf;
                    ny = xf;
                } else if (rotateDegrees == 180) {
                    nx = width - 1 - xf;
                    ny = height - 1 - yf;
                } else if (rotateDegrees == 270) {
                    nx = yf;
                    ny = width - 1 - xf;
                } else {
                    nx = xf;
                    ny = yf;
                }
                output[ny * rotatedWidth + nx] = pixels16[y * width + x];
            }
        }
        return new ImageMatrix(rotatedWidth, rotatedHeight, output);
    }

    private GeometryComputation applyTiltCorrection(ImageMatrix input,
                                                    Roi roi,
                                                    String dispersionAxis,
                                                    boolean enabled,
                                                    int maxShiftPixels) {
        if (!enabled || maxShiftPixels <= 0) {
            return GeometryComputation.notApplied(input, maxShiftPixels);
        }
        if ("X".equals(dispersionAxis)) {
            return alignRows(input, roi, maxShiftPixels);
        }
        return alignColumns(input, roi, maxShiftPixels);
    }

    private GeometryComputation alignRows(ImageMatrix input, Roi roi, int maxShiftPixels) {
        double[] reference = meanProfileX(input.pixels16, input.width, roi);
        double referenceMean = mean(reference);
        short[] output = new short[input.pixels16.length];
        int[] shifts = new int[input.height];
        for (int y = 0; y < input.height; y++) {
            int shift = y >= roi.yStart && y < roi.yEnd
                    ? bestShiftForRow(input.pixels16, input.width, y, roi, reference, referenceMean, maxShiftPixels)
                    : 0;
            shifts[y] = shift;
            for (int x = 0; x < input.width; x++) {
                int sourceX = x + shift;
                output[y * input.width + x] = sourceX < 0 || sourceX >= input.width
                        ? input.pixels16[y * input.width + x]
                        : input.pixels16[y * input.width + sourceX];
            }
        }
        return GeometryComputation.applied(new ImageMatrix(input.width, input.height, output), maxShiftPixels, shifts);
    }

    private GeometryComputation alignColumns(ImageMatrix input, Roi roi, int maxShiftPixels) {
        double[] reference = meanProfileY(input.pixels16, input.width, roi);
        double referenceMean = mean(reference);
        short[] output = new short[input.pixels16.length];
        int[] shifts = new int[input.width];
        for (int x = 0; x < input.width; x++) {
            int shift = x >= roi.xStart && x < roi.xEnd
                    ? bestShiftForColumn(input.pixels16, input.width, x, roi, reference, referenceMean, maxShiftPixels)
                    : 0;
            shifts[x] = shift;
            for (int y = 0; y < input.height; y++) {
                int sourceY = y + shift;
                output[y * input.width + x] = sourceY < 0 || sourceY >= input.height
                        ? input.pixels16[y * input.width + x]
                        : input.pixels16[sourceY * input.width + x];
            }
        }
        return GeometryComputation.applied(new ImageMatrix(input.width, input.height, output), maxShiftPixels, shifts);
    }

    private AxisDetection detectAxis(short[] pixels16, int imageWidth, Roi roi) {
        double[] xProfile = meanProfileX(pixels16, imageWidth, roi);
        double[] yProfile = meanProfileY(pixels16, imageWidth, roi);
        double xScore = coefficientOfVariation(xProfile) + 0.35d * derivativeEnergy(xProfile);
        double yScore = coefficientOfVariation(yProfile) + 0.35d * derivativeEnergy(yProfile);
        double confidence = Math.abs(xScore - yScore) / Math.max(Math.max(xScore, yScore), 1.0e-6d);
        return new AxisDetection(xScore >= yScore ? "X" : "Y", xScore, yScore, confidence);
    }

    private double[] meanProfileX(short[] pixels16, int imageWidth, Roi roi) {
        double[] profile = new double[roi.width()];
        for (int localX = 0; localX < roi.width(); localX++) {
            int x = roi.xStart + localX;
            double sum = 0.0d;
            for (int y = roi.yStart; y < roi.yEnd; y++) {
                sum += pixelAt(pixels16, imageWidth, x, y);
            }
            profile[localX] = sum / (double) roi.height();
        }
        return profile;
    }

    private double[] meanProfileY(short[] pixels16, int imageWidth, Roi roi) {
        double[] profile = new double[roi.height()];
        for (int localY = 0; localY < roi.height(); localY++) {
            int y = roi.yStart + localY;
            double sum = 0.0d;
            for (int x = roi.xStart; x < roi.xEnd; x++) {
                sum += pixelAt(pixels16, imageWidth, x, y);
            }
            profile[localY] = sum / (double) roi.width();
        }
        return profile;
    }

    private int bestShiftForRow(short[] pixels16,
                                int imageWidth,
                                int y,
                                Roi roi,
                                double[] reference,
                                double referenceMean,
                                int maxShiftPixels) {
        double rowMean = 0.0d;
        for (int x = roi.xStart; x < roi.xEnd; x++) {
            rowMean += pixelAt(pixels16, imageWidth, x, y);
        }
        rowMean /= (double) roi.width();

        int bestShift = 0;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int shift = -maxShiftPixels; shift <= maxShiftPixels; shift++) {
            double score = 0.0d;
            double rowNorm = 0.0d;
            double refNorm = 0.0d;
            for (int localX = 0; localX < roi.width(); localX++) {
                int sourceX = roi.xStart + localX + shift;
                if (sourceX < roi.xStart || sourceX >= roi.xEnd) {
                    continue;
                }
                double rowValue = pixelAt(pixels16, imageWidth, sourceX, y) - rowMean;
                double refValue = reference[localX] - referenceMean;
                score += rowValue * refValue;
                rowNorm += rowValue * rowValue;
                refNorm += refValue * refValue;
            }
            double normalized = score / Math.sqrt(Math.max(rowNorm * refNorm, 1.0d));
            if (normalized > bestScore) {
                bestScore = normalized;
                bestShift = shift;
            }
        }
        return bestShift;
    }

    private int bestShiftForColumn(short[] pixels16,
                                   int imageWidth,
                                   int x,
                                   Roi roi,
                                   double[] reference,
                                   double referenceMean,
                                   int maxShiftPixels) {
        double columnMean = 0.0d;
        for (int y = roi.yStart; y < roi.yEnd; y++) {
            columnMean += pixelAt(pixels16, imageWidth, x, y);
        }
        columnMean /= (double) roi.height();

        int bestShift = 0;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int shift = -maxShiftPixels; shift <= maxShiftPixels; shift++) {
            double score = 0.0d;
            double columnNorm = 0.0d;
            double refNorm = 0.0d;
            for (int localY = 0; localY < roi.height(); localY++) {
                int sourceY = roi.yStart + localY + shift;
                if (sourceY < roi.yStart || sourceY >= roi.yEnd) {
                    continue;
                }
                double columnValue = pixelAt(pixels16, imageWidth, x, sourceY) - columnMean;
                double refValue = reference[localY] - referenceMean;
                score += columnValue * refValue;
                columnNorm += columnValue * columnValue;
                refNorm += refValue * refValue;
            }
            double normalized = score / Math.sqrt(Math.max(columnNorm * refNorm, 1.0d));
            if (normalized > bestScore) {
                bestScore = normalized;
                bestShift = shift;
            }
        }
        return bestShift;
    }

    private Map<String, Object> orientationMap(CorrectionPlan plan, ImageSource source, ImageMatrix oriented) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("rotateDegrees", plan.rotateDegrees);
        map.put("flipX", plan.flipX);
        map.put("flipY", plan.flipY);
        map.put("orientationApplied", plan.rotateDegrees != 0 || plan.flipX || plan.flipY);
        map.put("inputWidth", source.width);
        map.put("inputHeight", source.height);
        map.put("outputWidth", oriented.width);
        map.put("outputHeight", oriented.height);
        map.put("operationOrder", "flipX/flipY first, then clockwise rotation");
        return map;
    }

    private Roi normalizeRoi(SpectrumExtractionRequest.Roi input, int width, int height) {
        int xStart = clamp(input == null || input.getXStart() == null ? 0 : input.getXStart(), 0, width - 1);
        int yStart = clamp(input == null || input.getYStart() == null ? 0 : input.getYStart(), 0, height - 1);
        int xEnd = clamp(input == null || input.getXEnd() == null ? width : input.getXEnd(), xStart + 1, width);
        int yEnd = clamp(input == null || input.getYEnd() == null ? height : input.getYEnd(), yStart + 1, height);
        if (xEnd - xStart < 4 || yEnd - yStart < 4) {
            throw new IllegalArgumentException("ROI过小，至少需要4x4像素");
        }
        return new Roi(xStart, xEnd, yStart, yEnd);
    }

    private Roi normalizeNullableRoi(SpectrumExtractionRequest.Roi input) {
        if (input == null) {
            return new Roi(null, null, null, null);
        }
        return new Roi(input.getXStart(), input.getXEnd(), input.getYStart(), input.getYEnd());
    }

    private Roi roiFromMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return new Roi(null, null, null, null);
        }
        return new Roi(
                asNullableInteger(map.get("xStart")),
                asNullableInteger(map.get("xEnd")),
                asNullableInteger(map.get("yStart")),
                asNullableInteger(map.get("yEnd")));
    }

    private String normalizeModeType(String modeType, String fallback) {
        if (modeType == null || modeType.trim().isEmpty()) {
            return fallback;
        }
        String mode = modeType.trim().toUpperCase(Locale.ROOT);
        if ("HDR".equals(mode) || "HDR_DARK".equals(mode) || "HDR_FLAT".equals(mode)) {
            return "HDR";
        }
        if ("NORMAL".equals(mode) || "DARK".equals(mode) || "FLAT".equals(mode)) {
            return "NORMAL";
        }
        return fallback;
    }

    private String normalizeSourceMode(String sourceMode, boolean allowAuto) {
        if (sourceMode == null || sourceMode.trim().isEmpty()) {
            return "AUTO";
        }
        String mode = sourceMode.trim().toUpperCase(Locale.ROOT);
        if ((allowAuto && "AUTO".equals(mode))
                || "ORIGINAL".equals(mode)
                || "CALIBRATED".equals(mode)
                || "PROCESSED".equals(mode)) {
            return mode;
        }
        return "AUTO";
    }

    private String normalizeAxis(String axis) {
        if (axis == null || axis.trim().isEmpty()) {
            return "AUTO";
        }
        String normalized = axis.trim().toUpperCase(Locale.ROOT);
        if ("X".equals(normalized) || "Y".equals(normalized) || "AUTO".equals(normalized)) {
            return normalized;
        }
        return "AUTO";
    }

    private int normalizeRotate(Integer rotateDegrees) {
        if (rotateDegrees == null) {
            return 0;
        }
        int normalized = ((rotateDegrees % 360) + 360) % 360;
        if (normalized == 90 || normalized == 180 || normalized == 270) {
            return normalized;
        }
        return 0;
    }

    private int normalizeMaxShift(Integer requested, Roi roi, String dispersionAxis) {
        int wavelengthLength = "X".equals(dispersionAxis) ? roi.width() : roi.height();
        int fallback = Math.max(2, Math.min(40, wavelengthLength / 20));
        if (requested == null) {
            return fallback;
        }
        return Math.max(0, Math.min(Math.abs(requested), Math.max(1, wavelengthLength / 4)));
    }

    private double coefficientOfVariation(double[] values) {
        double mean = mean(values);
        double sumSquares = 0.0d;
        for (double value : values) {
            double diff = value - mean;
            sumSquares += diff * diff;
        }
        return Math.sqrt(sumSquares / Math.max(values.length, 1)) / Math.max(Math.abs(mean), 1.0d);
    }

    private double derivativeEnergy(double[] values) {
        if (values.length < 2) {
            return 0.0d;
        }
        double sum = 0.0d;
        for (int i = 1; i < values.length; i++) {
            sum += Math.abs(values[i] - values[i - 1]);
        }
        return sum / (double) (values.length - 1) / Math.max(Math.abs(mean(values)), 1.0d);
    }

    private double mean(double[] values) {
        double sum = 0.0d;
        for (double value : values) {
            sum += value;
        }
        return sum / Math.max(values.length, 1);
    }

    private int pixelAt(short[] pixels16, int width, int x, int y) {
        return pixels16[y * width + x] & SENSOR_MAX_DN;
    }

    private short[] readRaw16Le(Path rawFile, int expectedPixels) {
        try {
            byte[] bytes = Files.readAllBytes(rawFile);
            if (bytes.length != expectedPixels * 2) {
                throw new IllegalStateException("RAW文件长度不匹配，期望 " + (expectedPixels * 2) + " 字节，实际 " + bytes.length);
            }
            short[] pixels16 = new short[expectedPixels];
            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < expectedPixels; i++) {
                pixels16[i] = (short) (buffer.getShort() & SENSOR_MAX_DN);
            }
            return pixels16;
        } catch (IOException ex) {
            throw new IllegalStateException("读取RAW图像失败: " + ex.getMessage(), ex);
        }
    }

    private byte[] toLittleEndianRawBytes(short[] pixels16) {
        ByteBuffer buffer = ByteBuffer.allocate(pixels16.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short pixel : pixels16) {
            buffer.putShort((short) (pixel & SENSOR_MAX_DN));
        }
        return buffer.array();
    }

    private byte[] toPreviewBytes(short[] pixels16) {
        byte[] pixels8 = new byte[pixels16.length];
        for (int i = 0; i < pixels16.length; i++) {
            int raw12 = pixels16[i] & SENSOR_MAX_DN;
            pixels8[i] = (byte) ((raw12 * 255) / SENSOR_MAX_DN);
        }
        return pixels8;
    }

    private void writePreviewPng(int width, int height, byte[] pixels8, Path target) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        byte[] targetBuffer = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        System.arraycopy(pixels8, 0, targetBuffer, 0, targetBuffer.length);
        if (!ImageIO.write(image, "png", target.toFile())) {
            throw new IOException("当前JRE没有可用的PNG编码器");
        }
    }

    private Path resolveStorageUri(String uri) {
        if (uri == null || uri.trim().isEmpty()) {
            throw new IllegalStateException("图像存储地址为空");
        }
        Path root = getStorageRoot();
        String relative = uri.replace('\\', '/');
        if (relative.startsWith("spectral://")) {
            relative = relative.substring("spectral://".length());
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalStateException("非法存储地址: " + uri);
        }
        return resolved;
    }

    private String toStorageUri(Path file) {
        return getStorageRoot().relativize(file.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
    }

    private Path getStorageRoot() {
        return Paths.get(storageRoot).toAbsolutePath().normalize();
    }

    private String encodePreviewDataUrl(Path previewFile) {
        if (previewFile == null) {
            return "";
        }
        try {
            byte[] bytes = Files.readAllBytes(previewFile);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (IOException ex) {
            return "";
        }
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException ex) {
            return Collections.emptyMap();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("JSON序列化失败: " + ex.getMessage(), ex);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return Collections.emptyMap();
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Integer asNullableInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private int asInteger(Object value, int fallback) {
        Integer parsed = asNullableInteger(value);
        return parsed == null ? fallback : parsed;
    }

    private double asDouble(Object value, double fallback) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class ImageSource {
        private long imageId;
        private long captureId;
        private String modeType;
        private int width;
        private int height;
        private String rawStorageUri;
        private String calibratedRawStorageUri;
        private String originalQualityStatus;
        private Map<String, Object> qualityDetails = Collections.emptyMap();
        private Map<String, Object> processingDetails = Collections.emptyMap();
    }

    private static final class SelectedSource {
        private final String sourceMode;
        private final String qualityStatus;
        private final String rawStorageUri;
        private final boolean preprocessingApplied;
        private final boolean applyCapturedCalibration;
        private final Map<String, Object> preprocessingDetails;

        private SelectedSource(String sourceMode,
                               String qualityStatus,
                               String rawStorageUri,
                               boolean preprocessingApplied,
                               boolean applyCapturedCalibration,
                               Map<String, Object> preprocessingDetails) {
            this.sourceMode = sourceMode;
            this.qualityStatus = qualityStatus;
            this.rawStorageUri = rawStorageUri;
            this.preprocessingApplied = preprocessingApplied;
            this.applyCapturedCalibration = applyCapturedCalibration;
            this.preprocessingDetails = preprocessingDetails == null
                    ? Collections.emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(preprocessingDetails));
        }
    }

    private static final class CorrectionPlan {
        private Long profileId;
        private String profileName;
        private String modeType;
        private String sourceMode;
        private String dispersionAxis;
        private int rotateDegrees;
        private boolean flipX;
        private boolean flipY;
        private boolean tiltCorrectionEnabled;
        private Integer maxShiftPixels;
        private SpectrumExtractionRequest.Roi roiInput;

        private static CorrectionPlan defaults(String modeType) {
            CorrectionPlan plan = new CorrectionPlan();
            plan.profileName = modeType + "几何校正配置";
            plan.modeType = modeType;
            plan.sourceMode = "AUTO";
            plan.dispersionAxis = "AUTO";
            plan.rotateDegrees = 0;
            plan.flipX = false;
            plan.flipY = false;
            plan.tiltCorrectionEnabled = true;
            return plan;
        }

        private static CorrectionPlan fromProfile(GeometryProfileRow profile) {
            CorrectionPlan plan = defaults(profile.modeType);
            plan.profileId = profile.id;
            plan.profileName = profile.profileName;
            plan.sourceMode = profile.sourceMode;
            plan.dispersionAxis = profile.dispersionAxis;
            plan.rotateDegrees = profile.rotateDegrees == null ? 0 : profile.rotateDegrees;
            plan.flipX = Boolean.TRUE.equals(profile.flipX);
            plan.flipY = Boolean.TRUE.equals(profile.flipY);
            plan.tiltCorrectionEnabled = profile.tiltCorrectionEnabled == null || profile.tiltCorrectionEnabled;
            plan.maxShiftPixels = profile.maxShiftPixels;
            plan.roiInput = profile.roi.toRequestRoi();
            return plan;
        }

        private GeometryProfileRow toProfileRow(Long userId, int width, int height) {
            GeometryProfileRow row = new GeometryProfileRow();
            row.profileName = profileName == null || profileName.trim().isEmpty()
                    ? modeType + "几何校正配置"
                    : profileName;
            row.modeType = modeType;
            row.imageWidth = width;
            row.imageHeight = height;
            row.sourceMode = sourceMode;
            row.dispersionAxis = dispersionAxis;
            row.rotateDegrees = rotateDegrees;
            row.flipX = flipX;
            row.flipY = flipY;
            row.tiltCorrectionEnabled = tiltCorrectionEnabled;
            row.maxShiftPixels = maxShiftPixels;
            row.roi = Roi.fromRequest(roiInput);
            row.enabled = true;
            row.details = new LinkedHashMap<>();
            row.details.put("savedFromImageCorrection", true);
            row.details.put("userId", userId);
            return row;
        }
    }

    private static final class GeometryProfileRow {
        private Long id;
        private String profileName;
        private String modeType;
        private Integer imageWidth;
        private Integer imageHeight;
        private String sourceMode;
        private String dispersionAxis;
        private Integer rotateDegrees;
        private Boolean flipX;
        private Boolean flipY;
        private Boolean tiltCorrectionEnabled;
        private Integer maxShiftPixels;
        private Roi roi;
        private Boolean enabled;
        private String algorithmVersion;
        private Map<String, Object> details = Collections.emptyMap();
        private OffsetDateTime createdAt;
        private OffsetDateTime updatedAt;

        private GeometryProfileRequest toRequest() {
            GeometryProfileRequest request = new GeometryProfileRequest();
            request.setId(id);
            request.setProfileName(profileName);
            request.setModeType(modeType);
            request.setSourceMode(sourceMode);
            request.setDispersionAxis(dispersionAxis);
            request.setRotateDegrees(rotateDegrees);
            request.setFlipX(flipX);
            request.setFlipY(flipY);
            request.setTiltCorrectionEnabled(tiltCorrectionEnabled);
            request.setMaxShiftPixels(maxShiftPixels);
            request.setRoi(roi == null ? null : roi.toRequestRoi());
            request.setEnabled(enabled);
            request.setDetails(details);
            return request;
        }

        private GeometryProfileResponse toResponse() {
            GeometryProfileResponse response = new GeometryProfileResponse();
            response.setId(id);
            response.setProfileName(profileName);
            response.setModeType(modeType);
            response.setImageWidth(imageWidth);
            response.setImageHeight(imageHeight);
            response.setSourceMode(sourceMode);
            response.setDispersionAxis(dispersionAxis);
            response.setRotateDegrees(rotateDegrees);
            response.setFlipX(flipX);
            response.setFlipY(flipY);
            response.setTiltCorrectionEnabled(tiltCorrectionEnabled);
            response.setMaxShiftPixels(maxShiftPixels);
            response.setRoi(roi == null ? null : roi.toResponseRoi());
            response.setEnabled(enabled);
            response.setAlgorithmVersion(algorithmVersion == null ? ALGORITHM_VERSION : algorithmVersion);
            response.setDetails(details);
            response.setCreatedAt(createdAt);
            response.setUpdatedAt(updatedAt);
            return response;
        }
    }

    private static final class CorrectionBuild {
        private final ImageSource source;
        private final SelectedSource selectedSource;
        private final CorrectionPlan plan;
        private final ImageMatrix corrected;
        private final Roi roi;
        private final String dispersionAxis;
        private final double axisConfidence;
        private final GeometryComputation computation;
        private final Map<String, Object> details;
        private final String summaryMessage;

        private CorrectionBuild(ImageSource source,
                                SelectedSource selectedSource,
                                CorrectionPlan plan,
                                ImageMatrix corrected,
                                Roi roi,
                                String dispersionAxis,
                                double axisConfidence,
                                GeometryComputation computation,
                                Map<String, Object> details,
                                String summaryMessage) {
            this.source = source;
            this.selectedSource = selectedSource;
            this.plan = plan;
            this.corrected = corrected;
            this.roi = roi;
            this.dispersionAxis = dispersionAxis;
            this.axisConfidence = axisConfidence;
            this.computation = computation;
            this.details = details;
            this.summaryMessage = summaryMessage;
        }
    }

    private static final class ImageMatrix {
        private final int width;
        private final int height;
        private final short[] pixels16;

        private ImageMatrix(int width, int height, short[] pixels16) {
            this.width = width;
            this.height = height;
            this.pixels16 = pixels16;
        }
    }

    private static final class Roi {
        private final Integer xStart;
        private final Integer xEnd;
        private final Integer yStart;
        private final Integer yEnd;

        private Roi(Integer xStart, Integer xEnd, Integer yStart, Integer yEnd) {
            this.xStart = xStart;
            this.xEnd = xEnd;
            this.yStart = yStart;
            this.yEnd = yEnd;
        }

        private static Roi fromRequest(SpectrumExtractionRequest.Roi roi) {
            return roi == null
                    ? new Roi(null, null, null, null)
                    : new Roi(roi.getXStart(), roi.getXEnd(), roi.getYStart(), roi.getYEnd());
        }

        private int width() {
            return (xEnd == null || xStart == null) ? 0 : xEnd - xStart;
        }

        private int height() {
            return (yEnd == null || yStart == null) ? 0 : yEnd - yStart;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("xStart", xStart);
            map.put("xEnd", xEnd);
            map.put("yStart", yStart);
            map.put("yEnd", yEnd);
            return map;
        }

        private SpectrumExtractionRequest.Roi toRequestRoi() {
            if (xStart == null && xEnd == null && yStart == null && yEnd == null) {
                return null;
            }
            SpectrumExtractionRequest.Roi roi = new SpectrumExtractionRequest.Roi();
            roi.setXStart(xStart);
            roi.setXEnd(xEnd);
            roi.setYStart(yStart);
            roi.setYEnd(yEnd);
            return roi;
        }

        private SpectrumExtractionResponse.Roi toResponseRoi() {
            SpectrumExtractionResponse.Roi roi = new SpectrumExtractionResponse.Roi();
            roi.setXStart(xStart);
            roi.setXEnd(xEnd);
            roi.setYStart(yStart);
            roi.setYEnd(yEnd);
            return roi;
        }
    }

    private static final class AxisDetection {
        private final String axis;
        private final double xScore;
        private final double yScore;
        private final double confidence;

        private AxisDetection(String axis, double xScore, double yScore, double confidence) {
            this.axis = axis;
            this.xScore = xScore;
            this.yScore = yScore;
            this.confidence = confidence;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("axis", axis);
            map.put("xScore", xScore);
            map.put("yScore", yScore);
            map.put("confidence", confidence);
            map.put("principle", "比较X/Y方向平均投影的一维变化程度；谱线沿波长方向变化更丰富。");
            return map;
        }
    }

    private static final class GeometryComputation {
        private final ImageMatrix matrix;
        private final boolean applied;
        private final int maxShiftPixels;
        private final int shiftMin;
        private final int shiftMax;
        private final double shiftMeanAbs;

        private GeometryComputation(ImageMatrix matrix,
                                    boolean applied,
                                    int maxShiftPixels,
                                    int[] shifts) {
            this.matrix = matrix;
            this.applied = applied;
            this.maxShiftPixels = maxShiftPixels;
            int min = 0;
            int max = 0;
            double sumAbs = 0.0d;
            if (shifts.length > 0) {
                min = shifts[0];
                max = shifts[0];
                for (int shift : shifts) {
                    min = Math.min(min, shift);
                    max = Math.max(max, shift);
                    sumAbs += Math.abs(shift);
                }
            }
            this.shiftMin = min;
            this.shiftMax = max;
            this.shiftMeanAbs = sumAbs / Math.max(shifts.length, 1);
        }

        private static GeometryComputation notApplied(ImageMatrix matrix, int maxShiftPixels) {
            return new GeometryComputation(matrix, false, maxShiftPixels, new int[0]);
        }

        private static GeometryComputation applied(ImageMatrix matrix, int maxShiftPixels, int[] shifts) {
            return new GeometryComputation(matrix, true, maxShiftPixels, shifts);
        }

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("applied", applied);
            map.put("maxShiftPixels", maxShiftPixels);
            map.put("shiftMin", shiftMin);
            map.put("shiftMax", shiftMax);
            map.put("shiftMeanAbs", shiftMeanAbs);
            map.put("method", applied
                    ? "integer-pixel normalized cross-correlation per row/column"
                    : "orientation only; tilt correction skipped");
            return map;
        }
    }

    private static final class SavedCorrection {
        private final long id;
        private final OffsetDateTime createdAt;

        private SavedCorrection(long id, OffsetDateTime createdAt) {
            this.id = id;
            this.createdAt = createdAt;
        }
    }

    @Getter
    public static final class CorrectedSource {
        private final long correctionId;
        private final long imageId;
        private final long captureId;
        private final Long profileId;
        private final String sourceQualityStatus;
        private final String rawStorageUri;
        private final int width;
        private final int height;
        private final String dispersionAxis;
        private final Map<String, Object> roi;
        private final Map<String, Object> details;
        private final String summaryMessage;

        private CorrectedSource(long correctionId,
                                long imageId,
                                long captureId,
                                Long profileId,
                                String sourceQualityStatus,
                                String rawStorageUri,
                                int width,
                                int height,
                                String dispersionAxis,
                                Map<String, Object> roi,
                                Map<String, Object> details,
                                String summaryMessage) {
            this.correctionId = correctionId;
            this.imageId = imageId;
            this.captureId = captureId;
            this.profileId = profileId;
            this.sourceQualityStatus = sourceQualityStatus;
            this.rawStorageUri = rawStorageUri;
            this.width = width;
            this.height = height;
            this.dispersionAxis = dispersionAxis;
            this.roi = roi == null ? Collections.<String, Object>emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(roi));
            this.details = details == null ? Collections.<String, Object>emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(details));
            this.summaryMessage = summaryMessage;
        }
    }
}
