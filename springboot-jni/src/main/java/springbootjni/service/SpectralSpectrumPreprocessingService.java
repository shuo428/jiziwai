package springbootjni.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import springbootjni.dto.jni.SpectrumExtractionResponse;
import springbootjni.dto.jni.SpectrumPreprocessingRequest;
import springbootjni.dto.jni.SpectrumPreprocessingResponse;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 一维光谱预处理服务。
 *
 * <p>它处理的是 t_spectrum_extraction 中已经提取出的 pixelIndex-intensity 曲线，
 * 不直接改二维 RAW 图像。原因是二维图像处理已经负责“能不能提取光谱”，而一维预处理负责
 * “提取后的曲线是否更适合展示、比对、拟合和后续分析”。这样职责边界更清楚，也能避免在二维
 * 阶段过度去噪、增强导致定量强度被提前改坏。</p>
 *
 * <p>当前第一版采用成熟光谱数据处理中常见且可解释的顺序：
 * 孤立尖刺修正 -> 曲线平滑 -> 背景/基线扣除 -> 强度归一化。
 * 其中背景扣除和归一化默认关闭，因为它们会改变曲线的基线或量纲，适合由研究人员按实验目的开启。</p>
 */
@Service
@RequiredArgsConstructor
public class SpectralSpectrumPreprocessingService {
    public static final String ALGORITHM_VERSION = "pixel-spectrum-preprocessing-v1";

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {
    };
    private static final TypeReference<List<SpectrumExtractionResponse.Point>> POINT_LIST_TYPE =
            new TypeReference<List<SpectrumExtractionResponse.Point>>() {
            };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SpectralSpectrumExtractionService spectrumExtractionService;

    @PostConstruct
    public void ensureSpectrumPreprocessingTable() {
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS t_spectrum_preprocessing (" +
                        "id BIGSERIAL PRIMARY KEY, " +
                        "spectrum_id BIGINT NOT NULL REFERENCES t_spectrum_extraction(id) ON DELETE CASCADE, " +
                        "image_id BIGINT NOT NULL REFERENCES t_spectral_image(id) ON DELETE CASCADE, " +
                        "capture_id BIGINT NOT NULL REFERENCES t_spectral_capture(id) ON DELETE CASCADE, " +
                        "user_id BIGINT REFERENCES t_user(id) ON DELETE SET NULL, " +
                        "preprocessing_steps JSONB NOT NULL DEFAULT '{}'::JSONB, " +
                        "point_count INTEGER NOT NULL, " +
                        "original_intensity_min NUMERIC(20,8), " +
                        "original_intensity_max NUMERIC(20,8), " +
                        "original_intensity_mean NUMERIC(20,8), " +
                        "processed_intensity_min NUMERIC(20,8), " +
                        "processed_intensity_max NUMERIC(20,8), " +
                        "processed_intensity_mean NUMERIC(20,8), " +
                        "dynamic_range_before NUMERIC(20,8), " +
                        "dynamic_range_after NUMERIC(20,8), " +
                        "noise_before NUMERIC(20,8), " +
                        "noise_after NUMERIC(20,8), " +
                        "spike_count INTEGER NOT NULL DEFAULT 0, " +
                        "processed_points JSONB NOT NULL, " +
                        "algorithm_version VARCHAR(64) NOT NULL, " +
                        "summary_message TEXT, " +
                        "details JSONB NOT NULL DEFAULT '{}'::JSONB, " +
                        "created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP" +
                        ")");
        jdbcTemplate.execute(
                "CREATE UNIQUE INDEX IF NOT EXISTS ux_spectrum_preprocessing_spectrum " +
                        "ON t_spectrum_preprocessing(spectrum_id)");
        jdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_spectrum_preprocessing_image " +
                        "ON t_spectrum_preprocessing(image_id, created_at DESC)");
    }

    @Transactional
    public SpectrumPreprocessingResponse preprocess(Long userId,
                                                    long imageId,
                                                    SpectrumPreprocessingRequest request) {
        SpectrumExtractionResponse sourceSpectrum = spectrumExtractionService.getLatest(userId, imageId);
        if (sourceSpectrum == null || sourceSpectrum.getId() == null) {
            throw new IllegalStateException("当前图片还没有一维光谱提取结果，请先提取一维光谱");
        }

        List<SpectrumExtractionResponse.Point> originalPoints = sanitizePoints(sourceSpectrum.getPoints());
        if (originalPoints.size() < 3) {
            throw new IllegalStateException("一维光谱点数过少，不能进行预处理");
        }

        NormalizedOptions options = NormalizedOptions.from(request, originalPoints.size());
        double[] originalValues = toValues(originalPoints);
        Stats originalStats = Stats.from(originalValues);
        double originalNoise = estimateRobustNoise(originalValues);

        double[] current = originalValues.clone();
        List<Map<String, Object>> executedSteps = new ArrayList<>();
        SpikeResult spikeResult = SpikeResult.empty(current);

        if (options.spikeCorrectionEnabled) {
            spikeResult = correctIsolatedSpikes(current, options.spikeWindowRadius, options.spikeThresholdMad);
            current = spikeResult.values;
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("name", "ISOLATED_SPIKE_CORRECTION");
            step.put("enabled", true);
            step.put("windowRadius", options.spikeWindowRadius);
            step.put("thresholdSigma", options.spikeThresholdMad);
            step.put("correctedPointCount", spikeResult.spikeCount);
            step.put("principle", "用邻域中值估计当前位置应有强度，若当前点偏离超过鲁棒噪声阈值，则替换为邻域中值");
            executedSteps.add(step);
        } else {
            executedSteps.add(disabledStep("ISOLATED_SPIKE_CORRECTION"));
        }

        if (options.smoothingEnabled) {
            String method = options.smoothingMethod;
            if ("SAVITZKY_GOLAY".equals(method)) {
                current = smoothSavitzkyGolay(current, options.smoothingWindow, options.smoothingPolynomialOrder);
            } else {
                current = smoothMovingAverage(current, options.smoothingWindow);
            }
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("name", "SMOOTHING");
            step.put("enabled", true);
            step.put("method", method);
            step.put("window", options.smoothingWindow);
            step.put("polynomialOrder", "SAVITZKY_GOLAY".equals(method) ? options.smoothingPolynomialOrder : null);
            step.put("principle", "在局部窗口内降低高频随机噪声；Savitzky-Golay通过局部多项式拟合尽量保留谱峰形状");
            executedSteps.add(step);
        } else {
            executedSteps.add(disabledStep("SMOOTHING"));
        }

        BaselineResult baselineResult = BaselineResult.empty(current);
        if (options.baselineCorrectionEnabled) {
            baselineResult = correctRollingPercentileBaseline(
                    current,
                    options.baselineWindow,
                    options.baselinePercentile);
            current = baselineResult.correctedValues;
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("name", "BASELINE_CORRECTION");
            step.put("enabled", true);
            step.put("method", options.baselineMethod);
            step.put("window", options.baselineWindow);
            step.put("percentile", options.baselinePercentile);
            step.put("baselineMin", baselineResult.stats.min);
            step.put("baselineMax", baselineResult.stats.max);
            step.put("baselineMean", baselineResult.stats.mean);
            step.put("principle", "用滑动窗口低分位数估计缓慢变化背景，再从光谱强度中扣除并截断负值");
            executedSteps.add(step);
        } else {
            executedSteps.add(disabledStep("BASELINE_CORRECTION"));
        }

        NormalizationResult normalizationResult = NormalizationResult.disabled(current);
        if (options.normalizationEnabled) {
            normalizationResult = normalizeIntensity(current, options.normalizationMethod);
            current = normalizationResult.values;
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("name", "NORMALIZATION");
            step.put("enabled", true);
            step.put("method", options.normalizationMethod);
            step.put("scaleFactor", normalizationResult.scaleFactor);
            step.put("principle", "把曲线转换到相对强度尺度，便于不同曝光或不同采集批次之间比较");
            executedSteps.add(step);
        } else {
            executedSteps.add(disabledStep("NORMALIZATION"));
        }

        Stats processedStats = Stats.from(current);
        double processedNoise = estimateRobustNoise(current);
        List<SpectrumExtractionResponse.Point> processedPoints = buildPoints(originalPoints, current);

        Map<String, Object> preprocessingSteps = options.toMap();
        preprocessingSteps.put("executionOrder", "spikeCorrection -> smoothing -> baselineCorrection -> normalization");
        preprocessingSteps.put("executedSteps", executedSteps);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("algorithmVersion", ALGORITHM_VERSION);
        details.put("sourceSpectrumId", sourceSpectrum.getId());
        details.put("sourceImageId", sourceSpectrum.getImageId());
        details.put("sourceMode", sourceSpectrum.getSourceMode());
        details.put("sourceQualityStatus", sourceSpectrum.getSourceQualityStatus());
        details.put("wavelengthAxis", sourceSpectrum.getWavelengthAxis());
        details.put("integrationMethod", sourceSpectrum.getIntegrationMethod());
        details.put("roi", sourceSpectrum.getRoi());
        details.put("geometryCorrectionApplied", sourceSpectrum.getGeometryCorrectionApplied());
        details.put("spikeIndexes", spikeResult.correctedIndexes);
        details.put("baseline", baselineResult.details);
        details.put("normalization", normalizationResult.details);
        details.put("noiseMetric", "robust MAD of adjacent first differences divided by sqrt(2)");
        details.put("intensityUnit", options.normalizationEnabled ? "relative" : "DN-derived intensity");

        String summaryMessage = buildSummaryMessage(options, spikeResult.spikeCount, originalNoise, processedNoise);
        SavedPreprocessing saved = jdbcTemplate.queryForObject(
                "INSERT INTO t_spectrum_preprocessing " +
                        "(spectrum_id, image_id, capture_id, user_id, preprocessing_steps, point_count, " +
                        "original_intensity_min, original_intensity_max, original_intensity_mean, " +
                        "processed_intensity_min, processed_intensity_max, processed_intensity_mean, " +
                        "dynamic_range_before, dynamic_range_after, noise_before, noise_after, spike_count, " +
                        "processed_points, algorithm_version, summary_message, details) " +
                        "VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " +
                        "CAST(? AS jsonb), ?, ?, CAST(? AS jsonb)) " +
                        "ON CONFLICT (spectrum_id) DO UPDATE SET " +
                        "image_id=EXCLUDED.image_id, " +
                        "capture_id=EXCLUDED.capture_id, " +
                        "user_id=EXCLUDED.user_id, " +
                        "preprocessing_steps=EXCLUDED.preprocessing_steps, " +
                        "point_count=EXCLUDED.point_count, " +
                        "original_intensity_min=EXCLUDED.original_intensity_min, " +
                        "original_intensity_max=EXCLUDED.original_intensity_max, " +
                        "original_intensity_mean=EXCLUDED.original_intensity_mean, " +
                        "processed_intensity_min=EXCLUDED.processed_intensity_min, " +
                        "processed_intensity_max=EXCLUDED.processed_intensity_max, " +
                        "processed_intensity_mean=EXCLUDED.processed_intensity_mean, " +
                        "dynamic_range_before=EXCLUDED.dynamic_range_before, " +
                        "dynamic_range_after=EXCLUDED.dynamic_range_after, " +
                        "noise_before=EXCLUDED.noise_before, " +
                        "noise_after=EXCLUDED.noise_after, " +
                        "spike_count=EXCLUDED.spike_count, " +
                        "processed_points=EXCLUDED.processed_points, " +
                        "algorithm_version=EXCLUDED.algorithm_version, " +
                        "summary_message=EXCLUDED.summary_message, " +
                        "details=EXCLUDED.details, " +
                        "created_at=CURRENT_TIMESTAMP " +
                        "RETURNING id, created_at",
                (resultSet, rowNum) -> new SavedPreprocessing(
                        resultSet.getLong("id"),
                        resultSet.getObject("created_at", OffsetDateTime.class)),
                sourceSpectrum.getId(),
                sourceSpectrum.getImageId(),
                sourceSpectrum.getCaptureId(),
                userId,
                toJson(preprocessingSteps),
                processedPoints.size(),
                toBigDecimal(originalStats.min),
                toBigDecimal(originalStats.max),
                toBigDecimal(originalStats.mean),
                toBigDecimal(processedStats.min),
                toBigDecimal(processedStats.max),
                toBigDecimal(processedStats.mean),
                toBigDecimal(originalStats.range()),
                toBigDecimal(processedStats.range()),
                toBigDecimal(originalNoise),
                toBigDecimal(processedNoise),
                spikeResult.spikeCount,
                toJson(processedPoints),
                ALGORITHM_VERSION,
                summaryMessage,
                toJson(details));

        /*
         * 预处理参数或处理后的点列发生变化时，旧峰中心、FWHM、面积和R²已不再对应
         * 当前预处理曲线。只删除下游拟合，不删除原始提取曲线或刚刚覆盖保存的预处理结果。
         */
        jdbcTemplate.update(
                "DELETE FROM t_spectrum_analysis WHERE spectrum_id=? AND source='PREPROCESSED'",
                sourceSpectrum.getId());

        return buildResponse(
                saved.id,
                sourceSpectrum,
                preprocessingSteps,
                originalStats,
                processedStats,
                originalNoise,
                processedNoise,
                spikeResult.spikeCount,
                originalPoints,
                processedPoints,
                details,
                summaryMessage,
                saved.createdAt);
    }

    @Transactional(readOnly = true)
    public SpectrumPreprocessingResponse getLatest(Long userId, long imageId) {
        List<SpectrumPreprocessingResponse> rows = jdbcTemplate.query(
                "SELECT sp.id, sp.spectrum_id, sp.image_id, sp.capture_id, " +
                        "sp.preprocessing_steps::text AS preprocessing_steps_json, sp.point_count, " +
                        "sp.original_intensity_min, sp.original_intensity_max, sp.original_intensity_mean, " +
                        "sp.processed_intensity_min, sp.processed_intensity_max, sp.processed_intensity_mean, " +
                        "sp.dynamic_range_before, sp.dynamic_range_after, sp.noise_before, sp.noise_after, " +
                        "sp.spike_count, sp.processed_points::text AS processed_points_json, " +
                        "sp.algorithm_version, sp.summary_message, sp.details::text AS details_json, sp.created_at, " +
                        "se.spectrum_points::text AS original_points_json " +
                        "FROM t_spectrum_preprocessing sp " +
                        "JOIN t_spectrum_extraction se ON se.id=sp.spectrum_id " +
                        "JOIN t_spectral_capture c ON c.id=sp.capture_id " +
                        "WHERE sp.image_id=? AND c.user_id=? " +
                        "ORDER BY sp.created_at DESC LIMIT 1",
                (resultSet, rowNum) -> mapPreprocessingResponse(resultSet),
                imageId,
                userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private SpectrumPreprocessingResponse buildResponse(Long preprocessingId,
                                                        SpectrumExtractionResponse sourceSpectrum,
                                                        Map<String, Object> preprocessingSteps,
                                                        Stats originalStats,
                                                        Stats processedStats,
                                                        double originalNoise,
                                                        double processedNoise,
                                                        int spikeCount,
                                                        List<SpectrumExtractionResponse.Point> originalPoints,
                                                        List<SpectrumExtractionResponse.Point> processedPoints,
                                                        Map<String, Object> details,
                                                        String summaryMessage,
                                                        OffsetDateTime createdAt) {
        SpectrumPreprocessingResponse response = new SpectrumPreprocessingResponse();
        response.setId(preprocessingId);
        response.setSpectrumId(sourceSpectrum.getId());
        response.setImageId(sourceSpectrum.getImageId());
        response.setCaptureId(sourceSpectrum.getCaptureId());
        response.setPreprocessingSteps(preprocessingSteps);
        response.setPointCount(processedPoints.size());
        response.setOriginalIntensityMin(originalStats.min);
        response.setOriginalIntensityMax(originalStats.max);
        response.setOriginalIntensityMean(originalStats.mean);
        response.setProcessedIntensityMin(processedStats.min);
        response.setProcessedIntensityMax(processedStats.max);
        response.setProcessedIntensityMean(processedStats.mean);
        response.setDynamicRangeBefore(originalStats.range());
        response.setDynamicRangeAfter(processedStats.range());
        response.setNoiseBefore(originalNoise);
        response.setNoiseAfter(processedNoise);
        response.setSpikeCount(spikeCount);
        response.setOriginalPoints(originalPoints);
        response.setPoints(processedPoints);
        response.setAlgorithmVersion(ALGORITHM_VERSION);
        response.setSummaryMessage(summaryMessage);
        response.setDetails(details);
        response.setCreatedAt(createdAt == null ? OffsetDateTime.now(ZoneOffset.UTC) : createdAt);
        return response;
    }

    private SpectrumPreprocessingResponse mapPreprocessingResponse(ResultSet resultSet) throws SQLException {
        SpectrumPreprocessingResponse response = new SpectrumPreprocessingResponse();
        response.setId(resultSet.getLong("id"));
        response.setSpectrumId(resultSet.getLong("spectrum_id"));
        response.setImageId(resultSet.getLong("image_id"));
        response.setCaptureId(resultSet.getLong("capture_id"));
        response.setPreprocessingSteps(parseJsonMap(resultSet.getString("preprocessing_steps_json")));
        response.setPointCount(resultSet.getInt("point_count"));
        response.setOriginalIntensityMin(getNullableDouble(resultSet, "original_intensity_min"));
        response.setOriginalIntensityMax(getNullableDouble(resultSet, "original_intensity_max"));
        response.setOriginalIntensityMean(getNullableDouble(resultSet, "original_intensity_mean"));
        response.setProcessedIntensityMin(getNullableDouble(resultSet, "processed_intensity_min"));
        response.setProcessedIntensityMax(getNullableDouble(resultSet, "processed_intensity_max"));
        response.setProcessedIntensityMean(getNullableDouble(resultSet, "processed_intensity_mean"));
        response.setDynamicRangeBefore(getNullableDouble(resultSet, "dynamic_range_before"));
        response.setDynamicRangeAfter(getNullableDouble(resultSet, "dynamic_range_after"));
        response.setNoiseBefore(getNullableDouble(resultSet, "noise_before"));
        response.setNoiseAfter(getNullableDouble(resultSet, "noise_after"));
        response.setSpikeCount(resultSet.getInt("spike_count"));
        response.setOriginalPoints(parsePointList(resultSet.getString("original_points_json")));
        response.setPoints(parsePointList(resultSet.getString("processed_points_json")));
        response.setAlgorithmVersion(resultSet.getString("algorithm_version"));
        response.setSummaryMessage(resultSet.getString("summary_message"));
        response.setDetails(parseJsonMap(resultSet.getString("details_json")));
        response.setCreatedAt(resultSet.getObject("created_at", OffsetDateTime.class));
        return response;
    }

    private List<SpectrumExtractionResponse.Point> sanitizePoints(List<SpectrumExtractionResponse.Point> points) {
        if (points == null || points.isEmpty()) {
            return Collections.emptyList();
        }
        List<SpectrumExtractionResponse.Point> sanitized = new ArrayList<>();
        for (SpectrumExtractionResponse.Point point : points) {
            if (point == null || point.getPixelIndex() == null || point.getIntensity() == null) {
                continue;
            }
            double intensity = point.getIntensity();
            if (!Double.isFinite(intensity)) {
                continue;
            }
            sanitized.add(new SpectrumExtractionResponse.Point(point.getPixelIndex(), intensity));
        }
        Collections.sort(sanitized, Comparator.comparingInt(SpectrumExtractionResponse.Point::getPixelIndex));
        return sanitized;
    }

    private double[] toValues(List<SpectrumExtractionResponse.Point> points) {
        double[] values = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            values[i] = points.get(i).getIntensity();
        }
        return values;
    }

    private List<SpectrumExtractionResponse.Point> buildPoints(List<SpectrumExtractionResponse.Point> originalPoints,
                                                               double[] values) {
        List<SpectrumExtractionResponse.Point> points = new ArrayList<>(values.length);
        for (int i = 0; i < values.length; i++) {
            points.add(new SpectrumExtractionResponse.Point(originalPoints.get(i).getPixelIndex(), sanitizeDouble(values[i])));
        }
        return points;
    }

    /**
     * 孤立尖刺修正：
     * 1. 对每个点，用左右邻域点的中值估计“当前位置附近的正常水平”；
     * 2. 计算当前点与邻域中值的差值；
     * 3. 用所有点的这种差值中位数构建 MAD 鲁棒噪声尺度；
     * 4. 如果某点相对邻域中值的偏差大于 thresholdSigma * MAD噪声，
     *    且它与左右相邻点也发生明显跳变，就认为它是孤立尖刺；
     * 5. 修正时不做复杂拟合，直接用邻域中值替换，避免引入新的峰形。
     */
    private SpikeResult correctIsolatedSpikes(double[] values, int radius, double thresholdSigma) {
        double[] residuals = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            residuals[i] = Math.abs(values[i] - localMedian(values, i, radius, true));
        }
        double residualMedian = median(residuals);
        double[] residualDeviation = new double[residuals.length];
        for (int i = 0; i < residuals.length; i++) {
            residualDeviation[i] = Math.abs(residuals[i] - residualMedian);
        }
        double sigma = 1.4826d * median(residualDeviation);
        if (sigma <= 1.0e-9d) {
            sigma = Math.max(1.0e-9d, estimateRobustNoise(values));
        }
        double threshold = Math.max(1.0e-9d, thresholdSigma * sigma);
        double[] corrected = values.clone();
        List<Integer> correctedIndexes = new ArrayList<>();

        int spikeCount = 0;
        for (int i = 0; i < values.length; i++) {
            double localMedian = localMedian(values, i, radius, true);
            double diff = Math.abs(values[i] - localMedian);
            double leftJump = i > 0 ? Math.abs(values[i] - values[i - 1]) : diff;
            double rightJump = i < values.length - 1 ? Math.abs(values[i] - values[i + 1]) : diff;
            boolean isolatedJump = Math.min(leftJump, rightJump) > threshold * 0.5d;
            if (diff > threshold && isolatedJump) {
                corrected[i] = localMedian;
                spikeCount++;
                if (correctedIndexes.size() < 200) {
                    correctedIndexes.add(i);
                }
            }
        }
        return new SpikeResult(corrected, spikeCount, correctedIndexes);
    }

    private double[] smoothMovingAverage(double[] values, int window) {
        int half = window / 2;
        double[] output = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            int start = Math.max(0, i - half);
            int end = Math.min(values.length - 1, i + half);
            double sum = 0.0d;
            int count = 0;
            for (int j = start; j <= end; j++) {
                sum += values[j];
                count++;
            }
            output[i] = sum / Math.max(count, 1);
        }
        return output;
    }

    /**
     * Savitzky-Golay 平滑：
     * 对每个中心点取一个局部窗口，把窗口内的曲线拟合成低阶多项式。
     * 拟合完成后只取多项式在中心点 x=0 的值作为平滑值。
     *
     * <p>这样做比简单移动平均更适合光谱曲线，因为它不是简单地把峰值“抹平”，而是在局部窗口里尽量
     * 保留峰形和峰位，同时压低高频噪声。边缘处窗口会自动截断，多项式阶数也会随可用点数降低。</p>
     */
    private double[] smoothSavitzkyGolay(double[] values, int window, int polynomialOrder) {
        int half = window / 2;
        double[] output = new double[values.length];
        for (int center = 0; center < values.length; center++) {
            int start = Math.max(0, center - half);
            int end = Math.min(values.length - 1, center + half);
            int count = end - start + 1;
            int order = Math.min(polynomialOrder, count - 1);
            if (order < 1) {
                output[center] = values[center];
                continue;
            }
            double[][] normal = new double[order + 1][order + 1];
            double[] rhs = new double[order + 1];
            for (int index = start; index <= end; index++) {
                double x = index - center;
                double[] powers = new double[order * 2 + 1];
                powers[0] = 1.0d;
                for (int p = 1; p < powers.length; p++) {
                    powers[p] = powers[p - 1] * x;
                }
                for (int row = 0; row <= order; row++) {
                    rhs[row] += values[index] * powers[row];
                    for (int col = 0; col <= order; col++) {
                        normal[row][col] += powers[row + col];
                    }
                }
            }
            double[] coefficients = solveLinearSystem(normal, rhs);
            output[center] = coefficients == null ? values[center] : coefficients[0];
        }
        return output;
    }

    private BaselineResult correctRollingPercentileBaseline(double[] values, int window, double percentile) {
        int half = window / 2;
        double[] baseline = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            int start = Math.max(0, i - half);
            int end = Math.min(values.length - 1, i + half);
            double[] local = new double[end - start + 1];
            for (int j = start; j <= end; j++) {
                local[j - start] = values[j];
            }
            baseline[i] = percentile(local, percentile);
        }
        // 低分位数序列本身可能有台阶，再做一次轻微平滑，让背景是缓慢变化的。
        double[] smoothBaseline = smoothMovingAverage(baseline, Math.min(window, 51));
        double[] corrected = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            corrected[i] = Math.max(0.0d, values[i] - smoothBaseline[i]);
        }
        Stats baselineStats = Stats.from(smoothBaseline);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("method", "ROLLING_PERCENTILE");
        details.put("window", window);
        details.put("percentile", percentile);
        details.put("baselineMin", baselineStats.min);
        details.put("baselineMax", baselineStats.max);
        details.put("baselineMean", baselineStats.mean);
        return new BaselineResult(corrected, baselineStats, details);
    }

    private NormalizationResult normalizeIntensity(double[] values, String method) {
        String normalizedMethod = method == null ? "MAX" : method.toUpperCase(Locale.ROOT);
        double[] output = values.clone();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("method", normalizedMethod);
        double scaleFactor = 1.0d;
        Stats stats = Stats.from(values);

        if ("MIN_MAX".equals(normalizedMethod)) {
            double range = stats.range();
            if (range > 1.0e-12d) {
                for (int i = 0; i < output.length; i++) {
                    output[i] = (values[i] - stats.min) / range;
                }
                scaleFactor = range;
            }
        } else if ("AREA".equals(normalizedMethod)) {
            double area = 0.0d;
            for (double value : values) {
                area += Math.max(0.0d, value);
            }
            if (area > 1.0e-12d) {
                for (int i = 0; i < output.length; i++) {
                    output[i] = values[i] / area;
                }
                scaleFactor = area;
            }
        } else {
            double max = stats.max;
            if (max > 1.0e-12d) {
                for (int i = 0; i < output.length; i++) {
                    output[i] = values[i] / max;
                }
                scaleFactor = max;
            }
            normalizedMethod = "MAX";
        }
        details.put("scaleFactor", scaleFactor);
        details.put("outputUnit", "relative");
        return new NormalizationResult(output, scaleFactor, details);
    }

    private double estimateRobustNoise(double[] values) {
        if (values.length < 3) {
            return 0.0d;
        }
        double[] differences = new double[values.length - 1];
        for (int i = 0; i < values.length - 1; i++) {
            differences[i] = values[i + 1] - values[i];
        }
        double median = median(differences);
        double[] deviations = new double[differences.length];
        for (int i = 0; i < differences.length; i++) {
            deviations[i] = Math.abs(differences[i] - median);
        }
        return 1.4826d * median(deviations) / Math.sqrt(2.0d);
    }

    private double localMedian(double[] values, int center, int radius, boolean excludeCenter) {
        int start = Math.max(0, center - radius);
        int end = Math.min(values.length - 1, center + radius);
        List<Double> local = new ArrayList<>();
        for (int i = start; i <= end; i++) {
            if (excludeCenter && i == center) {
                continue;
            }
            local.add(values[i]);
        }
        if (local.isEmpty()) {
            return values[center];
        }
        Collections.sort(local);
        int middle = local.size() / 2;
        if (local.size() % 2 == 1) {
            return local.get(middle);
        }
        return (local.get(middle - 1) + local.get(middle)) / 2.0d;
    }

    private double median(double[] values) {
        if (values.length == 0) {
            return 0.0d;
        }
        double[] copy = values.clone();
        java.util.Arrays.sort(copy);
        int middle = copy.length / 2;
        if (copy.length % 2 == 1) {
            return copy[middle];
        }
        return (copy[middle - 1] + copy[middle]) / 2.0d;
    }

    private double percentile(double[] values, double percentile) {
        if (values.length == 0) {
            return 0.0d;
        }
        java.util.Arrays.sort(values);
        double position = (Math.max(0.0d, Math.min(100.0d, percentile)) / 100.0d) * (values.length - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) {
            return values[lower];
        }
        double ratio = position - lower;
        return values[lower] * (1.0d - ratio) + values[upper] * ratio;
    }

    private double[] solveLinearSystem(double[][] matrix, double[] rhs) {
        int n = rhs.length;
        double[][] a = new double[n][n];
        double[] b = rhs.clone();
        for (int i = 0; i < n; i++) {
            System.arraycopy(matrix[i], 0, a[i], 0, n);
        }

        for (int pivot = 0; pivot < n; pivot++) {
            int best = pivot;
            for (int row = pivot + 1; row < n; row++) {
                if (Math.abs(a[row][pivot]) > Math.abs(a[best][pivot])) {
                    best = row;
                }
            }
            if (Math.abs(a[best][pivot]) < 1.0e-12d) {
                return null;
            }
            if (best != pivot) {
                double[] tempRow = a[pivot];
                a[pivot] = a[best];
                a[best] = tempRow;
                double tempValue = b[pivot];
                b[pivot] = b[best];
                b[best] = tempValue;
            }
            double divisor = a[pivot][pivot];
            for (int col = pivot; col < n; col++) {
                a[pivot][col] /= divisor;
            }
            b[pivot] /= divisor;
            for (int row = 0; row < n; row++) {
                if (row == pivot) {
                    continue;
                }
                double factor = a[row][pivot];
                for (int col = pivot; col < n; col++) {
                    a[row][col] -= factor * a[pivot][col];
                }
                b[row] -= factor * b[pivot];
            }
        }
        return b;
    }

    private Map<String, Object> disabledStep(String name) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("name", name);
        step.put("enabled", false);
        return step;
    }

    private String buildSummaryMessage(NormalizedOptions options,
                                       int spikeCount,
                                       double originalNoise,
                                       double processedNoise) {
        String noiseChange;
        if (originalNoise <= 1.0e-12d) {
            noiseChange = "原始噪声接近0";
        } else {
            double ratio = processedNoise / originalNoise;
            noiseChange = "噪声约为处理前的" + String.format(Locale.ROOT, "%.2f", ratio);
        }
        return "已完成一维光谱预处理：尖刺修正" + (options.spikeCorrectionEnabled ? "启用" : "关闭") +
                "，平滑" + (options.smoothingEnabled ? options.smoothingMethod : "关闭") +
                "，背景扣除" + (options.baselineCorrectionEnabled ? "启用" : "关闭") +
                "，归一化" + (options.normalizationEnabled ? options.normalizationMethod : "关闭") +
                "；修正孤立尖刺 " + spikeCount + " 个，" + noiseChange + "。";
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

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("JSON序列化失败: " + ex.getMessage(), ex);
        }
    }

    private BigDecimal toBigDecimal(double value) {
        return Double.isFinite(value) ? BigDecimal.valueOf(value) : null;
    }

    private double getNullableDouble(ResultSet resultSet, String column) throws SQLException {
        double value = resultSet.getDouble(column);
        return resultSet.wasNull() ? 0.0d : value;
    }

    private double sanitizeDouble(double value) {
        return Double.isFinite(value) ? value : 0.0d;
    }

    private static final class SavedPreprocessing {
        private final long id;
        private final OffsetDateTime createdAt;

        private SavedPreprocessing(long id, OffsetDateTime createdAt) {
            this.id = id;
            this.createdAt = createdAt;
        }
    }

    private static final class NormalizedOptions {
        private final boolean spikeCorrectionEnabled;
        private final int spikeWindowRadius;
        private final double spikeThresholdMad;
        private final boolean smoothingEnabled;
        private final String smoothingMethod;
        private final int smoothingWindow;
        private final int smoothingPolynomialOrder;
        private final boolean baselineCorrectionEnabled;
        private final String baselineMethod;
        private final int baselineWindow;
        private final double baselinePercentile;
        private final boolean normalizationEnabled;
        private final String normalizationMethod;

        private NormalizedOptions(boolean spikeCorrectionEnabled,
                                  int spikeWindowRadius,
                                  double spikeThresholdMad,
                                  boolean smoothingEnabled,
                                  String smoothingMethod,
                                  int smoothingWindow,
                                  int smoothingPolynomialOrder,
                                  boolean baselineCorrectionEnabled,
                                  String baselineMethod,
                                  int baselineWindow,
                                  double baselinePercentile,
                                  boolean normalizationEnabled,
                                  String normalizationMethod) {
            this.spikeCorrectionEnabled = spikeCorrectionEnabled;
            this.spikeWindowRadius = spikeWindowRadius;
            this.spikeThresholdMad = spikeThresholdMad;
            this.smoothingEnabled = smoothingEnabled;
            this.smoothingMethod = smoothingMethod;
            this.smoothingWindow = smoothingWindow;
            this.smoothingPolynomialOrder = smoothingPolynomialOrder;
            this.baselineCorrectionEnabled = baselineCorrectionEnabled;
            this.baselineMethod = baselineMethod;
            this.baselineWindow = baselineWindow;
            this.baselinePercentile = baselinePercentile;
            this.normalizationEnabled = normalizationEnabled;
            this.normalizationMethod = normalizationMethod;
        }

        private static NormalizedOptions from(SpectrumPreprocessingRequest request, int pointCount) {
            boolean spikeEnabled = request == null || request.getSpikeCorrectionEnabled() == null
                    || request.getSpikeCorrectionEnabled();
            int spikeRadius = clamp(request == null || request.getSpikeWindowRadius() == null
                    ? 2 : request.getSpikeWindowRadius(), 1, 10);
            double spikeThreshold = clamp(request == null || request.getSpikeThresholdMad() == null
                    ? 8.0d : request.getSpikeThresholdMad(), 3.0d, 30.0d);

            boolean smoothingEnabled = request == null || request.getSmoothingEnabled() == null
                    || request.getSmoothingEnabled();
            String smoothingMethod = normalizeChoice(
                    request == null ? null : request.getSmoothingMethod(),
                    "SAVITZKY_GOLAY",
                    "MOVING_AVERAGE",
                    "SAVITZKY_GOLAY");
            int smoothingWindow = normalizeOddWindow(
                    request == null || request.getSmoothingWindow() == null ? 7 : request.getSmoothingWindow(),
                    3,
                    Math.min(51, Math.max(3, pointCount % 2 == 0 ? pointCount - 1 : pointCount)));
            int polynomialOrder = clamp(request == null || request.getSmoothingPolynomialOrder() == null
                    ? 2 : request.getSmoothingPolynomialOrder(), 1, Math.max(1, smoothingWindow - 1));

            boolean baselineEnabled = request != null
                    && request.getBaselineCorrectionEnabled() != null
                    && request.getBaselineCorrectionEnabled();
            String baselineMethod = normalizeChoice(
                    request == null ? null : request.getBaselineMethod(),
                    "ROLLING_PERCENTILE",
                    "ROLLING_PERCENTILE");
            int baselineWindow = normalizeOddWindow(
                    request == null || request.getBaselineWindow() == null ? 51 : request.getBaselineWindow(),
                    5,
                    Math.min(401, Math.max(5, pointCount % 2 == 0 ? pointCount - 1 : pointCount)));
            double baselinePercentile = clamp(request == null || request.getBaselinePercentile() == null
                    ? 10.0d : request.getBaselinePercentile(), 1.0d, 45.0d);

            boolean normalizationEnabled = request != null
                    && request.getNormalizationEnabled() != null
                    && request.getNormalizationEnabled();
            String normalizationMethod = normalizeChoice(
                    request == null ? null : request.getNormalizationMethod(),
                    "MAX",
                    "MAX",
                    "AREA",
                    "MIN_MAX");

            return new NormalizedOptions(
                    spikeEnabled,
                    spikeRadius,
                    spikeThreshold,
                    smoothingEnabled,
                    smoothingMethod,
                    smoothingWindow,
                    polynomialOrder,
                    baselineEnabled,
                    baselineMethod,
                    baselineWindow,
                    baselinePercentile,
                    normalizationEnabled,
                    normalizationEnabled ? normalizationMethod : "NONE");
        }

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("spikeCorrectionEnabled", spikeCorrectionEnabled);
            map.put("spikeWindowRadius", spikeWindowRadius);
            map.put("spikeThresholdMad", spikeThresholdMad);
            map.put("smoothingEnabled", smoothingEnabled);
            map.put("smoothingMethod", smoothingMethod);
            map.put("smoothingWindow", smoothingWindow);
            map.put("smoothingPolynomialOrder", smoothingPolynomialOrder);
            map.put("baselineCorrectionEnabled", baselineCorrectionEnabled);
            map.put("baselineMethod", baselineMethod);
            map.put("baselineWindow", baselineWindow);
            map.put("baselinePercentile", baselinePercentile);
            map.put("normalizationEnabled", normalizationEnabled);
            map.put("normalizationMethod", normalizationMethod);
            return map;
        }

        private static int normalizeOddWindow(int value, int min, int max) {
            int normalized = clamp(value, min, max);
            if (normalized % 2 == 0) {
                normalized++;
            }
            if (normalized > max) {
                normalized -= 2;
            }
            return Math.max(min, normalized);
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }

        private static double clamp(double value, double min, double max) {
            return Math.max(min, Math.min(max, value));
        }

        private static String normalizeChoice(String value, String fallback, String... allowed) {
            String normalized = value == null ? fallback : value.trim().toUpperCase(Locale.ROOT);
            for (String item : allowed) {
                if (item.equals(normalized)) {
                    return normalized;
                }
            }
            return fallback;
        }
    }

    private static final class Stats {
        private final double min;
        private final double max;
        private final double mean;

        private Stats(double min, double max, double mean) {
            this.min = min;
            this.max = max;
            this.mean = mean;
        }

        private static Stats from(double[] values) {
            if (values.length == 0) {
                return new Stats(0.0d, 0.0d, 0.0d);
            }
            double min = values[0];
            double max = values[0];
            double sum = 0.0d;
            for (double value : values) {
                min = Math.min(min, value);
                max = Math.max(max, value);
                sum += value;
            }
            return new Stats(min, max, sum / values.length);
        }

        private double range() {
            return max - min;
        }
    }

    private static final class SpikeResult {
        private final double[] values;
        private final int spikeCount;
        private final List<Integer> correctedIndexes;

        private SpikeResult(double[] values, int spikeCount, List<Integer> correctedIndexes) {
            this.values = values;
            this.spikeCount = spikeCount;
            this.correctedIndexes = correctedIndexes;
        }

        private static SpikeResult empty(double[] values) {
            return new SpikeResult(values, 0, Collections.<Integer>emptyList());
        }
    }

    private static final class BaselineResult {
        private final double[] correctedValues;
        private final Stats stats;
        private final Map<String, Object> details;

        private BaselineResult(double[] correctedValues,
                               Stats stats,
                               Map<String, Object> details) {
            this.correctedValues = correctedValues;
            this.stats = stats;
            this.details = details;
        }

        private static BaselineResult empty(double[] values) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("enabled", false);
            return new BaselineResult(values, new Stats(0.0d, 0.0d, 0.0d), details);
        }
    }

    private static final class NormalizationResult {
        private final double[] values;
        private final double scaleFactor;
        private final Map<String, Object> details;

        private NormalizationResult(double[] values,
                                    double scaleFactor,
                                    Map<String, Object> details) {
            this.values = values;
            this.scaleFactor = scaleFactor;
            this.details = details;
        }

        private static NormalizationResult disabled(double[] values) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("enabled", false);
            details.put("method", "NONE");
            return new NormalizationResult(values, 1.0d, details);
        }
    }
}
