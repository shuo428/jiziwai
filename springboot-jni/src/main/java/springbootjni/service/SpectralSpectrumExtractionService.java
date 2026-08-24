package springbootjni.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import springbootjni.dto.jni.SpectrumExtractionRequest;
import springbootjni.dto.jni.SpectrumExtractionResponse;

import javax.annotation.PostConstruct;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 单张图像的一维像素域光谱提取服务。
 *
 * <p>当前阶段不做 wavelength(nm) 标定，只输出 pixelIndex-intensity 曲线。这样即使暂时没有
 * 光栅、透镜和标定光源参数，也可以先完成“从合格图像提取一维光谱”的闭环。</p>
 *
 * <p>方向不固定或图像存在轻微倾斜时，第一版采用整数像素级互相关矫正：
 * 若 X 是波长方向，则逐行估计相对平均谱形的横向偏移并对齐后积分；
 * 若 Y 是波长方向，则逐列估计纵向偏移并对齐后积分。更复杂的弯曲/非线性矫正应在
 * 后续结合标定光源和谱线中心拟合实现。</p>
 */
@Service
@RequiredArgsConstructor
public class SpectralSpectrumExtractionService {
    public static final String ALGORITHM_VERSION = "pixel-spectrum-extraction-v1";

    private static final int SENSOR_MAX_DN = 4095;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {
    };
    private static final TypeReference<List<SpectrumExtractionResponse.Point>> POINT_LIST_TYPE =
            new TypeReference<List<SpectrumExtractionResponse.Point>>() {
            };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SpectralCalibrationService calibrationService;
    private final SpectralImageProcessingService imageProcessingService;

    @Value("${spectral.storage.root:D:/GraduationProject/spectral-images}")
    private String storageRoot;

    @PostConstruct
    public void ensureSpectrumExtractionTable() {
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS t_spectrum_extraction (" +
                        "id BIGSERIAL PRIMARY KEY, " +
                        "image_id BIGINT NOT NULL REFERENCES t_spectral_image(id) ON DELETE CASCADE, " +
                        "capture_id BIGINT NOT NULL REFERENCES t_spectral_capture(id) ON DELETE CASCADE, " +
                        "user_id BIGINT REFERENCES t_user(id) ON DELETE SET NULL, " +
                        "source_mode VARCHAR(16) NOT NULL CHECK (source_mode IN ('ORIGINAL','CALIBRATED','PROCESSED')), " +
                        "source_quality_status VARCHAR(16) NOT NULL, " +
                        "wavelength_axis VARCHAR(8) NOT NULL CHECK (wavelength_axis IN ('X','Y')), " +
                        "roi JSONB NOT NULL, " +
                        "rectified BOOLEAN NOT NULL DEFAULT TRUE, " +
                        "max_shift_pixels INTEGER NOT NULL DEFAULT 0, " +
                        "shift_summary JSONB NOT NULL DEFAULT '{}'::JSONB, " +
                        "integration_method VARCHAR(16) NOT NULL CHECK (integration_method IN ('MEAN','SUM')), " +
                        "point_count INTEGER NOT NULL, " +
                        "intensity_min NUMERIC(20,8), " +
                        "intensity_max NUMERIC(20,8), " +
                        "intensity_mean NUMERIC(20,8), " +
                        "spectrum_points JSONB NOT NULL, " +
                        "algorithm_version VARCHAR(48) NOT NULL, " +
                        "summary_message TEXT, " +
                        "details JSONB NOT NULL DEFAULT '{}'::JSONB, " +
                        "created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP" +
                        ")");
        jdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_spectrum_extraction_image " +
                        "ON t_spectrum_extraction(image_id, created_at DESC)");
        jdbcTemplate.execute(
                "ALTER TABLE IF EXISTS t_spectrum_extraction " +
                        "DROP CONSTRAINT IF EXISTS t_spectrum_extraction_source_mode_check");
        jdbcTemplate.execute(
                "ALTER TABLE IF EXISTS t_spectrum_extraction " +
                        "ADD CONSTRAINT t_spectrum_extraction_source_mode_check " +
                        "CHECK (source_mode IN ('ORIGINAL','CALIBRATED','PROCESSED'))");
        jdbcTemplate.execute(
                "DELETE FROM t_spectrum_extraction older USING t_spectrum_extraction newer " +
                        "WHERE older.image_id=newer.image_id " +
                        "AND (older.created_at < newer.created_at " +
                        "OR (older.created_at = newer.created_at AND older.id < newer.id))");
        jdbcTemplate.execute(
                "CREATE UNIQUE INDEX IF NOT EXISTS ux_spectrum_extraction_image " +
                        "ON t_spectrum_extraction(image_id)");
    }

    @Transactional
    public SpectrumExtractionResponse extract(Long userId,
                                              long imageId,
                                              SpectrumExtractionRequest request) {
        ImageSource source = loadImageSource(userId, imageId);
        SelectedSource selectedSource = selectSource(source, normalizeSourceMode(request));
        Roi roi = normalizeRoi(request == null ? null : request.getRoi(), source.width, source.height);
        short[] rawPixels16 = readRaw16Le(resolveStorageUri(selectedSource.rawStorageUri), source.width * source.height);
        short[] pixels16 = rawPixels16;
        Map<String, Object> calibrationDetails;
        if (selectedSource.applyCapturedCalibration) {
            // 兼容旧数据：如果采集时记录了校准包快照但当时还没有落盘 calibrated RAW，
            // 则按该快照临时恢复校准版像素，不能使用后来更新的全局包。
            SpectralCalibrationService.CalibrationProfile calibrationProfile = calibrationService.loadCapturedProfile(
                    userId, source.width, source.height, source.qualityDetails);
            SpectralCalibrationService.CalibrationApplicationResult calibration = calibrationProfile.apply(rawPixels16);
            pixels16 = calibration.getPixels16();
            Map<String, Object> details = new LinkedHashMap<>(calibration.getDetails());
            if (calibrationProfile.getDefectMap() != null) {
                SpectralImageProcessingService.ProcessingResult mapResult = imageProcessingService.processWithMultiFrameDefectMap(
                        source.width, source.height, pixels16, calibrationProfile.getDefectMap());
                if (mapResult != null) {
                    pixels16 = mapResult.getProcessedPixels16();
                    details.put("defectMapApplied", true);
                    details.put("defectMapExecutedActions", mapResult.getExecutedActions());
                } else {
                    details.put("defectMapApplied", false);
                }
            }
            calibrationDetails = details;
        } else if (selectedSource.preprocessingApplied) {
            calibrationDetails = new LinkedHashMap<>(selectedSource.preprocessingDetails);
            calibrationDetails.put("preprocessingApplied", true);
        } else {
            calibrationDetails = Collections.singletonMap("preprocessingApplied", false);
        }

        String wavelengthAxis = normalizeAxis(request);
        if ("AUTO".equals(wavelengthAxis)) {
            wavelengthAxis = detectWavelengthAxis(pixels16, source.width, roi);
        }
        boolean rectifyTilt = request == null || request.getRectifyTilt() == null || request.getRectifyTilt();
        String integrationMethod = normalizeIntegrationMethod(request);
        int maxShiftPixels = normalizeMaxShift(request, roi, wavelengthAxis);

        ExtractionComputation computation = "X".equals(wavelengthAxis)
                ? extractAlongX(pixels16, source.width, roi, rectifyTilt, maxShiftPixels, integrationMethod)
                : extractAlongY(pixels16, source.width, roi, rectifyTilt, maxShiftPixels, integrationMethod);

        Map<String, Object> roiMap = roi.toMap();
        Map<String, Object> shiftSummary = computation.shiftSummary();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("algorithmVersion", ALGORITHM_VERSION);
        details.put("sourceRawStorageUri", selectedSource.rawStorageUri);
        details.put("axisDetection", wavelengthAxis);
        details.put("roi", roiMap);
        details.put("rectification", shiftSummary);
        details.put("calibration", calibrationDetails);
        details.put("notes", "pixel-domain spectrum; wavelength calibration is not applied");

        String summaryMessage = buildSummaryMessage(selectedSource, wavelengthAxis, roi, computation);
        SavedExtraction savedExtraction = jdbcTemplate.queryForObject(
                "INSERT INTO t_spectrum_extraction " +
                        "(image_id, capture_id, user_id, source_mode, source_quality_status, wavelength_axis, " +
                        "roi, rectified, max_shift_pixels, shift_summary, integration_method, point_count, " +
                        "intensity_min, intensity_max, intensity_mean, spectrum_points, algorithm_version, " +
                        "summary_message, details) " +
                        "VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, " +
                        "CAST(? AS jsonb), ?, ?, CAST(? AS jsonb)) " +
                        "ON CONFLICT (image_id) DO UPDATE SET " +
                        "capture_id=EXCLUDED.capture_id, " +
                        "user_id=EXCLUDED.user_id, " +
                        "source_mode=EXCLUDED.source_mode, " +
                        "source_quality_status=EXCLUDED.source_quality_status, " +
                        "wavelength_axis=EXCLUDED.wavelength_axis, " +
                        "roi=EXCLUDED.roi, " +
                        "rectified=EXCLUDED.rectified, " +
                        "max_shift_pixels=EXCLUDED.max_shift_pixels, " +
                        "shift_summary=EXCLUDED.shift_summary, " +
                        "integration_method=EXCLUDED.integration_method, " +
                        "point_count=EXCLUDED.point_count, " +
                        "intensity_min=EXCLUDED.intensity_min, " +
                        "intensity_max=EXCLUDED.intensity_max, " +
                        "intensity_mean=EXCLUDED.intensity_mean, " +
                        "spectrum_points=EXCLUDED.spectrum_points, " +
                        "algorithm_version=EXCLUDED.algorithm_version, " +
                        "summary_message=EXCLUDED.summary_message, " +
                        "details=EXCLUDED.details, " +
                        "created_at=CURRENT_TIMESTAMP " +
                        "RETURNING id, created_at",
                (resultSet, rowNum) -> new SavedExtraction(
                        resultSet.getLong("id"),
                        resultSet.getObject("created_at", OffsetDateTime.class)),
                source.imageId,
                source.captureId,
                userId,
                selectedSource.sourceMode,
                selectedSource.qualityStatus,
                wavelengthAxis,
                toJson(roiMap),
                rectifyTilt,
                maxShiftPixels,
                toJson(shiftSummary),
                integrationMethod,
                computation.points.size(),
                BigDecimal.valueOf(computation.intensityMin),
                BigDecimal.valueOf(computation.intensityMax),
                BigDecimal.valueOf(computation.intensityMean),
                toJson(computation.points),
                ALGORITHM_VERSION,
                summaryMessage,
                toJson(details));

        return buildResponse(
                savedExtraction.id,
                source,
                selectedSource,
                wavelengthAxis,
                roi,
                rectifyTilt,
                maxShiftPixels,
                integrationMethod,
                computation,
                details,
                summaryMessage,
                savedExtraction.createdAt);
    }

    @Transactional(readOnly = true)
    public SpectrumExtractionResponse getLatest(Long userId, long imageId) {
        List<SpectrumExtractionResponse> rows = jdbcTemplate.query(
                "SELECT se.id, se.image_id, se.capture_id, se.source_mode, se.source_quality_status, " +
                        "se.wavelength_axis, se.roi::text AS roi_json, se.rectified, se.max_shift_pixels, " +
                        "se.shift_summary::text AS shift_summary_json, se.integration_method, se.point_count, " +
                        "se.intensity_min, se.intensity_max, se.intensity_mean, " +
                        "se.spectrum_points::text AS spectrum_points_json, se.algorithm_version, " +
                        "se.summary_message, se.details::text AS details_json, se.created_at " +
                        "FROM t_spectrum_extraction se " +
                        "JOIN t_spectral_capture c ON c.id=se.capture_id " +
                        "WHERE se.image_id=? AND c.user_id=? " +
                        "ORDER BY se.created_at DESC LIMIT 1",
                (resultSet, rowNum) -> mapExtractionResponse(resultSet),
                imageId,
                userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ImageSource loadImageSource(Long userId, long imageId) {
        List<ImageSource> rows = jdbcTemplate.query(
                "SELECT i.id AS image_id, i.capture_id, i.width, i.height, i.raw_storage_uri, " +
                        "i.calibrated_raw_storage_uri, " +
                "qa.quality_status, qa.details::text AS quality_details_json, al.details::text AS processing_details_json " +
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
                (resultSet, rowNum) -> mapImageSource(resultSet),
                imageId,
                userId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("图像不存在或不属于当前用户");
        }
        return rows.get(0);
    }

    private ImageSource mapImageSource(ResultSet resultSet) throws SQLException {
        ImageSource source = new ImageSource();
        source.imageId = resultSet.getLong("image_id");
        source.captureId = resultSet.getLong("capture_id");
        source.width = resultSet.getInt("width");
        source.height = resultSet.getInt("height");
        source.rawStorageUri = resultSet.getString("raw_storage_uri");
        source.calibratedRawStorageUri = resultSet.getString("calibrated_raw_storage_uri");
        source.originalQualityStatus = resultSet.getString("quality_status");
        source.qualityDetails = parseJsonMap(resultSet.getString("quality_details_json"));
        String processingDetailsJson = resultSet.getString("processing_details_json");
        source.processingDetails = parseJsonMap(processingDetailsJson);
        return source;
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
                throw new IllegalStateException("当前图片没有可用于光谱提取的处理后PASS结果");
            }
            return new SelectedSource(
                    "PROCESSED",
                    processedQualityStatus,
                    processedRawUri,
                    processedPreprocessingApplied,
                    false,
                    processedCalibration);
        }

        if ("CALIBRATED".equals(requestedMode)) {
            if (!calibratedPass) {
                throw new IllegalStateException("当前图片没有可用于光谱提取的校准后PASS结果");
            }
            if (calibratedRawAvailable) {
                return new SelectedSource(
                        "CALIBRATED",
                        source.originalQualityStatus,
                        source.calibratedRawStorageUri,
                        true,
                        false,
                        capturedCalibration);
            }
            return new SelectedSource(
                    "CALIBRATED",
                    source.originalQualityStatus,
                    source.rawStorageUri,
                    false,
                    true,
                    capturedCalibration);
        }

        if ("ORIGINAL".equals(requestedMode)) {
            if (!originalPass) {
                throw new IllegalStateException("原图质量不是PASS，不能直接提取一维光谱");
            }
            return new SelectedSource(
                    "ORIGINAL",
                    source.originalQualityStatus,
                    source.rawStorageUri,
                    false,
                    false,
                    Collections.singletonMap("preprocessingApplied", false));
        }

        if (processedPass) {
            return new SelectedSource(
                    "PROCESSED",
                    processedQualityStatus,
                    processedRawUri,
                    processedPreprocessingApplied,
                    false,
                    processedCalibration);
        }
        if (calibratedPass) {
            if (calibratedRawAvailable) {
                return new SelectedSource(
                        "CALIBRATED",
                        source.originalQualityStatus,
                        source.calibratedRawStorageUri,
                        true,
                        false,
                        capturedCalibration);
            }
            return new SelectedSource(
                    "CALIBRATED",
                    source.originalQualityStatus,
                    source.rawStorageUri,
                    false,
                    true,
                    capturedCalibration);
        }
        if (originalPass) {
            return new SelectedSource(
                    "ORIGINAL",
                    source.originalQualityStatus,
                    source.rawStorageUri,
                    false,
                    false,
                    Collections.singletonMap("preprocessingApplied", false));
        }
        throw new IllegalStateException("当前图片原图、校准后图和处理后结果均不是PASS，不能提取一维光谱");
    }

    private String normalizeSourceMode(SpectrumExtractionRequest request) {
        if (request == null || request.getSourceMode() == null || request.getSourceMode().trim().isEmpty()) {
            return "AUTO";
        }
        String mode = request.getSourceMode().trim().toUpperCase(Locale.ROOT);
        if ("AUTO".equals(mode)
                || "ORIGINAL".equals(mode)
                || "CALIBRATED".equals(mode)
                || "PROCESSED".equals(mode)) {
            return mode;
        }
        return "AUTO";
    }

    private String normalizeAxis(SpectrumExtractionRequest request) {
        if (request == null || request.getWavelengthAxis() == null || request.getWavelengthAxis().trim().isEmpty()) {
            return "AUTO";
        }
        String axis = request.getWavelengthAxis().trim().toUpperCase(Locale.ROOT);
        if ("X".equals(axis) || "Y".equals(axis) || "AUTO".equals(axis)) {
            return axis;
        }
        return "AUTO";
    }

    private String normalizeIntegrationMethod(SpectrumExtractionRequest request) {
        if (request == null || request.getIntegrationMethod() == null) {
            return "MEAN";
        }
        String method = request.getIntegrationMethod().trim().toUpperCase(Locale.ROOT);
        if ("SUM".equals(method) || "MEAN".equals(method)) {
            return method;
        }
        return "MEAN";
    }

    private int normalizeMaxShift(SpectrumExtractionRequest request, Roi roi, String wavelengthAxis) {
        int wavelengthLength = "X".equals(wavelengthAxis) ? roi.width() : roi.height();
        int fallback = Math.max(2, Math.min(40, wavelengthLength / 20));
        if (request == null || request.getMaxShiftPixels() == null) {
            return fallback;
        }
        return Math.max(0, Math.min(Math.abs(request.getMaxShiftPixels()), Math.max(1, wavelengthLength / 4)));
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

    private String detectWavelengthAxis(short[] pixels16, int imageWidth, Roi roi) {
        double[] xProfile = meanProfileX(pixels16, imageWidth, roi);
        double[] yProfile = meanProfileY(pixels16, imageWidth, roi);
        double xScore = coefficientOfVariation(xProfile);
        double yScore = coefficientOfVariation(yProfile);
        return xScore >= yScore ? "X" : "Y";
    }

    private ExtractionComputation extractAlongX(short[] pixels16,
                                                int imageWidth,
                                                Roi roi,
                                                boolean rectifyTilt,
                                                int maxShiftPixels,
                                                String integrationMethod) {
        int width = roi.width();
        int height = roi.height();
        double[] reference = meanProfileX(pixels16, imageWidth, roi);
        double referenceMean = mean(reference);
        double[] sums = new double[width];
        int[] counts = new int[width];
        int[] shifts = new int[height];

        for (int localY = 0; localY < height; localY++) {
            int y = roi.yStart + localY;
            int shift = rectifyTilt ? bestShiftForRow(pixels16, imageWidth, y, roi, reference, referenceMean, maxShiftPixels) : 0;
            shifts[localY] = shift;
            for (int localX = 0; localX < width; localX++) {
                int sourceX = roi.xStart + localX + shift;
                if (sourceX < roi.xStart || sourceX >= roi.xEnd) {
                    continue;
                }
                sums[localX] += pixelAt(pixels16, imageWidth, sourceX, y);
                counts[localX]++;
            }
        }
        return toComputation(sums, counts, shifts, integrationMethod);
    }

    private ExtractionComputation extractAlongY(short[] pixels16,
                                                int imageWidth,
                                                Roi roi,
                                                boolean rectifyTilt,
                                                int maxShiftPixels,
                                                String integrationMethod) {
        int width = roi.width();
        int height = roi.height();
        double[] reference = meanProfileY(pixels16, imageWidth, roi);
        double referenceMean = mean(reference);
        double[] sums = new double[height];
        int[] counts = new int[height];
        int[] shifts = new int[width];

        for (int localX = 0; localX < width; localX++) {
            int x = roi.xStart + localX;
            int shift = rectifyTilt ? bestShiftForColumn(pixels16, imageWidth, x, roi, reference, referenceMean, maxShiftPixels) : 0;
            shifts[localX] = shift;
            for (int localY = 0; localY < height; localY++) {
                int sourceY = roi.yStart + localY + shift;
                if (sourceY < roi.yStart || sourceY >= roi.yEnd) {
                    continue;
                }
                sums[localY] += pixelAt(pixels16, imageWidth, x, sourceY);
                counts[localY]++;
            }
        }
        return toComputation(sums, counts, shifts, integrationMethod);
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

    private ExtractionComputation toComputation(double[] sums, int[] counts, int[] shifts, String integrationMethod) {
        List<SpectrumExtractionResponse.Point> points = new ArrayList<>(sums.length);
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        double total = 0.0d;
        for (int index = 0; index < sums.length; index++) {
            double intensity;
            if ("SUM".equals(integrationMethod)) {
                intensity = sums[index];
            } else {
                intensity = counts[index] == 0 ? 0.0d : sums[index] / (double) counts[index];
            }
            points.add(new SpectrumExtractionResponse.Point(index, intensity));
            min = Math.min(min, intensity);
            max = Math.max(max, intensity);
            total += intensity;
        }
        return new ExtractionComputation(points, min, max, total / Math.max(points.size(), 1), shifts);
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

    private double coefficientOfVariation(double[] values) {
        double mean = mean(values);
        double sumSquares = 0.0d;
        for (double value : values) {
            double diff = value - mean;
            sumSquares += diff * diff;
        }
        double stddev = Math.sqrt(sumSquares / Math.max(values.length, 1));
        return stddev / Math.max(Math.abs(mean), 1.0d);
    }

    private double mean(double[] values) {
        double sum = 0.0d;
        for (double value : values) {
            sum += value;
        }
        return sum / Math.max(values.length, 1);
    }

    private short[] readRaw16Le(Path rawFile, int expectedPixels) {
        try {
            byte[] bytes = Files.readAllBytes(rawFile);
            if (bytes.length != expectedPixels * 2) {
                throw new IllegalStateException("RAW文件长度不匹配，期望 " + (expectedPixels * 2) + " 字节，实际 " + bytes.length);
            }
            short[] pixels16 = new short[expectedPixels];
            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            for (int index = 0; index < expectedPixels; index++) {
                pixels16[index] = (short) (buffer.getShort() & SENSOR_MAX_DN);
            }
            return pixels16;
        } catch (IOException ex) {
            throw new IllegalStateException("读取RAW图像失败: " + ex.getMessage(), ex);
        }
    }

    private int pixelAt(short[] pixels16, int width, int x, int y) {
        return pixels16[y * width + x] & SENSOR_MAX_DN;
    }

    private Path resolveStorageUri(String storageUri) {
        if (storageUri == null || storageUri.trim().isEmpty()) {
            throw new IllegalStateException("图像RAW存储地址为空");
        }
        Path root = Paths.get(storageRoot).toAbsolutePath().normalize();
        String relative = storageUri.replace('\\', '/');
        if (relative.startsWith("spectral://")) {
            relative = relative.substring("spectral://".length());
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalStateException("非法存储地址: " + storageUri);
        }
        return resolved;
    }

    private SpectrumExtractionResponse buildResponse(Long extractionId,
                                                     ImageSource source,
                                                     SelectedSource selectedSource,
                                                     String wavelengthAxis,
                                                     Roi roi,
                                                     boolean rectified,
                                                     int maxShiftPixels,
                                                     String integrationMethod,
                                                     ExtractionComputation computation,
                                                     Map<String, Object> details,
                                                     String summaryMessage,
                                                     OffsetDateTime createdAt) {
        SpectrumExtractionResponse response = new SpectrumExtractionResponse();
        response.setId(extractionId);
        response.setImageId(source.imageId);
        response.setCaptureId(source.captureId);
        response.setSourceMode(selectedSource.sourceMode);
        response.setSourceQualityStatus(selectedSource.qualityStatus);
        response.setWavelengthAxis(wavelengthAxis);
        response.setRoi(roi.toResponseRoi());
        response.setRectified(rectified);
        response.setMaxShiftPixels(maxShiftPixels);
        response.setShiftMin(computation.shiftMin);
        response.setShiftMax(computation.shiftMax);
        response.setShiftMeanAbs(computation.shiftMeanAbs);
        response.setIntegrationMethod(integrationMethod);
        response.setPointCount(computation.points.size());
        response.setIntensityMin(computation.intensityMin);
        response.setIntensityMax(computation.intensityMax);
        response.setIntensityMean(computation.intensityMean);
        response.setPoints(computation.points);
        response.setAlgorithmVersion(ALGORITHM_VERSION);
        response.setSummaryMessage(summaryMessage);
        response.setDetails(details);
        response.setCreatedAt(createdAt == null ? OffsetDateTime.now(ZoneOffset.UTC) : createdAt);
        return response;
    }

    private SpectrumExtractionResponse mapExtractionResponse(ResultSet resultSet) throws SQLException {
        SpectrumExtractionResponse response = new SpectrumExtractionResponse();
        response.setId(resultSet.getLong("id"));
        response.setImageId(resultSet.getLong("image_id"));
        response.setCaptureId(resultSet.getLong("capture_id"));
        response.setSourceMode(resultSet.getString("source_mode"));
        response.setSourceQualityStatus(resultSet.getString("source_quality_status"));
        response.setWavelengthAxis(resultSet.getString("wavelength_axis"));
        response.setRoi(roiFromMap(parseJsonMap(resultSet.getString("roi_json"))));
        response.setRectified(resultSet.getBoolean("rectified"));
        response.setMaxShiftPixels(resultSet.getInt("max_shift_pixels"));
        Map<String, Object> shiftSummary = parseJsonMap(resultSet.getString("shift_summary_json"));
        response.setShiftMin(asInteger(shiftSummary.get("shiftMin"), 0));
        response.setShiftMax(asInteger(shiftSummary.get("shiftMax"), 0));
        response.setShiftMeanAbs(asDouble(shiftSummary.get("shiftMeanAbs"), 0.0d));
        response.setIntegrationMethod(resultSet.getString("integration_method"));
        response.setPointCount(resultSet.getInt("point_count"));
        response.setIntensityMin(resultSet.getDouble("intensity_min"));
        response.setIntensityMax(resultSet.getDouble("intensity_max"));
        response.setIntensityMean(resultSet.getDouble("intensity_mean"));
        response.setPoints(parsePointList(resultSet.getString("spectrum_points_json")));
        response.setAlgorithmVersion(resultSet.getString("algorithm_version"));
        response.setSummaryMessage(resultSet.getString("summary_message"));
        response.setDetails(parseJsonMap(resultSet.getString("details_json")));
        response.setCreatedAt(resultSet.getObject("created_at", OffsetDateTime.class));
        return response;
    }

    private SpectrumExtractionResponse.Roi roiFromMap(Map<String, Object> map) {
        SpectrumExtractionResponse.Roi roi = new SpectrumExtractionResponse.Roi();
        roi.setXStart(asInteger(map.get("xStart"), 0));
        roi.setXEnd(asInteger(map.get("xEnd"), 0));
        roi.setYStart(asInteger(map.get("yStart"), 0));
        roi.setYEnd(asInteger(map.get("yEnd"), 0));
        return roi;
    }

    private List<SpectrumExtractionResponse.Point> parsePointList(String json) {
        if (json == null || json.trim().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, POINT_LIST_TYPE);
        } catch (JsonProcessingException ex) {
            return Collections.emptyList();
        }
    }

    private String buildSummaryMessage(SelectedSource selectedSource,
                                       String wavelengthAxis,
                                       Roi roi,
                                       ExtractionComputation computation) {
        return "已提取一维像素域光谱：来源=" + selectedSource.sourceMode +
                "，波长方向=" + wavelengthAxis +
                "，ROI=" + roi.width() + "x" + roi.height() +
                "，点数=" + computation.points.size() +
                "，偏移范围=" + computation.shiftMin + " 到 " + computation.shiftMax + " px。";
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

    private int asInteger(Object value, int fallback) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
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

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("JSON序列化失败: " + ex.getMessage(), ex);
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class ImageSource {
        private long imageId;
        private long captureId;
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

    private static final class SavedExtraction {
        private final long id;
        private final OffsetDateTime createdAt;

        private SavedExtraction(long id, OffsetDateTime createdAt) {
            this.id = id;
            this.createdAt = createdAt;
        }
    }

    private static final class Roi {
        private final int xStart;
        private final int xEnd;
        private final int yStart;
        private final int yEnd;

        private Roi(int xStart, int xEnd, int yStart, int yEnd) {
            this.xStart = xStart;
            this.xEnd = xEnd;
            this.yStart = yStart;
            this.yEnd = yEnd;
        }

        private int width() {
            return xEnd - xStart;
        }

        private int height() {
            return yEnd - yStart;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("xStart", xStart);
            map.put("xEnd", xEnd);
            map.put("yStart", yStart);
            map.put("yEnd", yEnd);
            return map;
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

    private static final class ExtractionComputation {
        private final List<SpectrumExtractionResponse.Point> points;
        private final double intensityMin;
        private final double intensityMax;
        private final double intensityMean;
        private final int shiftMin;
        private final int shiftMax;
        private final double shiftMeanAbs;

        private ExtractionComputation(List<SpectrumExtractionResponse.Point> points,
                                      double intensityMin,
                                      double intensityMax,
                                      double intensityMean,
                                      int[] shifts) {
            this.points = points;
            this.intensityMin = intensityMin;
            this.intensityMax = intensityMax;
            this.intensityMean = intensityMean;
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

        private Map<String, Object> shiftSummary() {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("shiftMin", shiftMin);
            summary.put("shiftMax", shiftMax);
            summary.put("shiftMeanAbs", shiftMeanAbs);
            return summary;
        }
    }
}
