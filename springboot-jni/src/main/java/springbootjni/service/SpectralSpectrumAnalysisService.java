package springbootjni.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import springbootjni.dto.jni.SpectrumAnalysisRequest;
import springbootjni.dto.jni.SpectrumAnalysisResponse;
import springbootjni.dto.jni.SpectrumExtractionResponse;
import springbootjni.dto.jni.SpectrumPreprocessingResponse;

import javax.annotation.PostConstruct;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 像素坐标域的一维光谱谱峰分析服务。
 *
 * <p>服务故意不产生伪造的nm波长。输入曲线的横坐标是 CMOS 光谱方向的 pixelIndex，
 * 因此输出的峰中心、半高宽和峰间距也都是像素单位。算法分为三个独立阶段：</p>
 * <ol>
 *     <li>从原始或已预处理的一维曲线中检测正向局部峰，并用突出度和鲁棒信噪比筛选；</li>
 *     <li>用最小间距非极大值抑制，防止宽谱峰被相邻局部起伏重复计数；</li>
 *     <li>对每个保留候选在局部窗口内拟合“高斯峰 + 线性基线”，输出中心、FWHM、面积和R²。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class SpectralSpectrumAnalysisService {
    public static final String ALGORITHM_VERSION = "pixel-spectrum-peak-analysis-v2-positive-only";
    // 旧负向分析保留在数据库，但不能作为正向峰结果回显。旧正向分析仍可读取。
    private static final String POSITIVE_RESULT_FILTER =
            "AND COALESCE(sa.analysis_parameters->>'peakPolarity', 'POSITIVE') = 'POSITIVE' ";
    private static final double SQRT_TWO_PI = Math.sqrt(2.0d * Math.PI);
    private static final double FWHM_FACTOR = 2.0d * Math.sqrt(2.0d * Math.log(2.0d));
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {
    };
    private static final TypeReference<List<SpectrumAnalysisResponse.Peak>> PEAK_LIST_TYPE =
            new TypeReference<List<SpectrumAnalysisResponse.Peak>>() {
            };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SpectralSpectrumExtractionService spectrumExtractionService;
    private final SpectralSpectrumPreprocessingService spectrumPreprocessingService;

    @PostConstruct
    public void ensureSpectrumAnalysisTable() {
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS t_spectrum_analysis (" +
                        "id BIGSERIAL PRIMARY KEY, " +
                        "spectrum_id BIGINT NOT NULL REFERENCES t_spectrum_extraction(id) ON DELETE CASCADE, " +
                        "preprocessing_id BIGINT REFERENCES t_spectrum_preprocessing(id) ON DELETE SET NULL, " +
                        "image_id BIGINT NOT NULL REFERENCES t_spectral_image(id) ON DELETE CASCADE, " +
                        "capture_id BIGINT NOT NULL REFERENCES t_spectral_capture(id) ON DELETE CASCADE, " +
                        "user_id BIGINT REFERENCES t_user(id) ON DELETE SET NULL, " +
                        "source VARCHAR(16) NOT NULL, " +
                        "point_count INTEGER NOT NULL, " +
                        "dynamic_range NUMERIC(20,8), " +
                        "noise_estimate NUMERIC(20,8), " +
                        "candidate_peak_count INTEGER NOT NULL DEFAULT 0, " +
                        "peak_count INTEGER NOT NULL DEFAULT 0, " +
                        "analysis_parameters JSONB NOT NULL DEFAULT '{}'::JSONB, " +
                        "peak_results JSONB NOT NULL DEFAULT '[]'::JSONB, " +
                        "algorithm_version VARCHAR(64) NOT NULL, " +
                        "summary_message TEXT, " +
                        "details JSONB NOT NULL DEFAULT '{}'::JSONB, " +
                        "created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP" +
                        ")");
        /*
         * 原始一维曲线与预处理曲线是两份不同的分析输入，允许它们各保留一份最新拟合结果。
         * 同一来源重复调整阈值或拟合窗口时才覆盖旧结果。
         */
        jdbcTemplate.execute("DROP INDEX IF EXISTS ux_spectrum_analysis_spectrum");
        jdbcTemplate.execute(
                "CREATE UNIQUE INDEX IF NOT EXISTS ux_spectrum_analysis_spectrum_source " +
                        "ON t_spectrum_analysis(spectrum_id, source)");
        jdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_spectrum_analysis_image " +
                        "ON t_spectrum_analysis(image_id, created_at DESC)");
    }

    /**
     * 对一张图像最新的一维光谱执行分析。同一个输入来源（原始或预处理）的旧分析会被覆盖，
     * 但两种来源的结果会并存，便于观察预处理是否改变峰形或误伤真实谱峰。
     */
    @Transactional
    public SpectrumAnalysisResponse analyze(Long userId, long imageId, SpectrumAnalysisRequest request) {
        SpectrumExtractionResponse extracted = spectrumExtractionService.getLatest(userId, imageId);
        if (extracted == null || extracted.getId() == null) {
            throw new IllegalStateException("当前图片还没有一维光谱提取结果，请先提取一维光谱");
        }

        SpectrumPreprocessingResponse preprocessing = spectrumPreprocessingService.getLatest(userId, imageId);
        InputCurve input = selectInput(extracted, preprocessing, request);
        if (input.points.size() < 7) {
            throw new IllegalStateException("一维光谱点数过少，至少需要7个点才能进行峰检测和拟合");
        }

        Options options = Options.from(request, input.points.size());
        double[] values = toValues(input.points);
        double dynamicRange = range(values);
        double noise = estimateRobustNoise(values, dynamicRange);
        double minProminence = Math.max(dynamicRange * options.minProminenceRatio, 1.0e-9d);

        List<Candidate> candidates = findCandidates(input.points, values, options, minProminence, noise);
        List<Candidate> selected = selectSeparatedCandidates(candidates, options.minDistancePixels, options.maxPeaks);
        List<SpectrumAnalysisResponse.Peak> peaks = new ArrayList<>();
        for (int index = 0; index < selected.size(); index++) {
            peaks.add(fitCandidate(index + 1, selected.get(index), input.points, values, options, noise));
        }

        Map<String, Object> parameters = options.toMap();
        parameters.put("coordinateUnit", "pixelIndex");
        parameters.put("minimumProminence", minProminence);
        parameters.put("robustNoiseMethod", "1.4826 * median(abs(diff - median(diff))) / sqrt(2)");
        parameters.put("fitModel", "amplitude * exp(-0.5 * ((x-center)/sigma)^2) + intercept + slope * (x-observedPeak)");

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("algorithmVersion", ALGORITHM_VERSION);
        details.put("coordinateMeaning", "像素位置，不是已标定波长nm");
        details.put("inputSpectrumId", extracted.getId());
        details.put("inputPreprocessingId", input.preprocessingId);
        details.put("inputSource", input.source);
        details.put("wavelengthAxis", extracted.getWavelengthAxis());
        details.put("integrationMethod", extracted.getIntegrationMethod());
        details.put("geometryCorrectionApplied", extracted.getGeometryCorrectionApplied());
        details.put("candidateSelection", "局部极大值 + 两侧局部背景突出度 + 鲁棒SNR + 最小间距非极大值抑制");
        details.put("fitInterpretation", "FWHM、峰面积与中心均为像素域结果；没有标定来源时不得解释为nm或物质谱线");
        details.put("qualityCounts", countFitQuality(peaks));

        String summary = buildSummary(input, candidates.size(), peaks);
        SavedAnalysis saved = jdbcTemplate.queryForObject(
                "INSERT INTO t_spectrum_analysis " +
                        "(spectrum_id, preprocessing_id, image_id, capture_id, user_id, source, point_count, " +
                        "dynamic_range, noise_estimate, candidate_peak_count, peak_count, analysis_parameters, " +
                        "peak_results, algorithm_version, summary_message, details) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, CAST(? AS jsonb)) " +
                        "ON CONFLICT (spectrum_id, source) DO UPDATE SET " +
                        "preprocessing_id=EXCLUDED.preprocessing_id, image_id=EXCLUDED.image_id, " +
                        "capture_id=EXCLUDED.capture_id, user_id=EXCLUDED.user_id, source=EXCLUDED.source, " +
                        "point_count=EXCLUDED.point_count, dynamic_range=EXCLUDED.dynamic_range, " +
                        "noise_estimate=EXCLUDED.noise_estimate, candidate_peak_count=EXCLUDED.candidate_peak_count, " +
                        "peak_count=EXCLUDED.peak_count, analysis_parameters=EXCLUDED.analysis_parameters, " +
                        "peak_results=EXCLUDED.peak_results, algorithm_version=EXCLUDED.algorithm_version, " +
                        "summary_message=EXCLUDED.summary_message, details=EXCLUDED.details, " +
                        "created_at=CURRENT_TIMESTAMP " +
                        "RETURNING id, created_at",
                (resultSet, rowNum) -> new SavedAnalysis(
                        resultSet.getLong("id"),
                        resultSet.getObject("created_at", OffsetDateTime.class)),
                extracted.getId(), input.preprocessingId, extracted.getImageId(), extracted.getCaptureId(), userId,
                input.source, input.points.size(), dynamicRange, noise, candidates.size(), peaks.size(),
                toJson(parameters), toJson(peaks), ALGORITHM_VERSION, summary, toJson(details));

        return buildResponse(saved.id, extracted, input, dynamicRange, noise, candidates.size(), peaks,
                parameters, details, summary, saved.createdAt);
    }

    @Transactional(readOnly = true)
    public SpectrumAnalysisResponse getLatest(Long userId, long imageId) {
        List<SpectrumAnalysisResponse> results = jdbcTemplate.query(
                "SELECT sa.id, sa.spectrum_id, sa.preprocessing_id, sa.image_id, sa.capture_id, sa.source, " +
                        "sa.point_count, sa.dynamic_range, sa.noise_estimate, sa.candidate_peak_count, sa.peak_count, " +
                        "sa.analysis_parameters::text AS analysis_parameters_json, sa.peak_results::text AS peak_results_json, " +
                        "sa.algorithm_version, sa.summary_message, sa.details::text AS details_json, sa.created_at " +
                        "FROM t_spectrum_analysis sa " +
                        "JOIN t_spectral_capture c ON c.id=sa.capture_id " +
                        "WHERE sa.image_id=? AND c.user_id=? " + POSITIVE_RESULT_FILTER +
                        "ORDER BY sa.created_at DESC LIMIT 1",
                (resultSet, rowNum) -> mapResponse(resultSet), imageId, userId);
        return results.isEmpty() ? null : results.get(0);
    }

    /** 同一图像按输入来源返回最新的原始曲线与预处理曲线分析，供详情工作台并排比较。 */
    @Transactional(readOnly = true)
    public List<SpectrumAnalysisResponse> listLatestBySource(Long userId, long imageId) {
        return jdbcTemplate.query(
                "SELECT sa.id, sa.spectrum_id, sa.preprocessing_id, sa.image_id, sa.capture_id, sa.source, " +
                        "sa.point_count, sa.dynamic_range, sa.noise_estimate, sa.candidate_peak_count, sa.peak_count, " +
                        "sa.analysis_parameters::text AS analysis_parameters_json, sa.peak_results::text AS peak_results_json, " +
                        "sa.algorithm_version, sa.summary_message, sa.details::text AS details_json, sa.created_at " +
                        "FROM t_spectrum_analysis sa " +
                        "JOIN t_spectral_capture c ON c.id=sa.capture_id " +
                        "WHERE sa.image_id=? AND c.user_id=? " + POSITIVE_RESULT_FILTER +
                        "ORDER BY CASE sa.source WHEN 'EXTRACTED' THEN 0 ELSE 1 END, sa.created_at DESC",
                (resultSet, rowNum) -> mapResponse(resultSet), imageId, userId);
    }

    private InputCurve selectInput(SpectrumExtractionResponse extracted,
                                   SpectrumPreprocessingResponse preprocessing,
                                   SpectrumAnalysisRequest request) {
        String requested = request == null || request.getSource() == null
                ? "AUTO" : request.getSource().trim().toUpperCase(Locale.ROOT);
        boolean hasPreprocessed = preprocessing != null
                && preprocessing.getSpectrumId() != null
                && preprocessing.getSpectrumId().equals(extracted.getId())
                && preprocessing.getPoints() != null
                && !preprocessing.getPoints().isEmpty();
        if ("PREPROCESSED".equals(requested) && !hasPreprocessed) {
            throw new IllegalStateException("当前图片没有可用的光谱预处理结果，请先执行预处理或改选原始一维光谱");
        }
        if (("AUTO".equals(requested) && hasPreprocessed) || "PREPROCESSED".equals(requested)) {
            return new InputCurve("PREPROCESSED", preprocessing.getId(), sanitizePoints(preprocessing.getPoints()));
        }
        if (!"AUTO".equals(requested) && !"EXTRACTED".equals(requested)) {
            throw new IllegalArgumentException("分析输入只能是 AUTO、EXTRACTED 或 PREPROCESSED");
        }
        return new InputCurve("EXTRACTED", null, sanitizePoints(extracted.getPoints()));
    }

    private List<Candidate> findCandidates(List<SpectrumExtractionResponse.Point> points,
                                           double[] values,
                                           Options options,
                                           double minProminence,
                                           double noise) {
        List<Candidate> result = new ArrayList<>();
        int neighborhood = Math.max(4, options.minDistancePixels * 2);
        for (int index = 1; index < values.length - 1; index++) {
            boolean localExtremum = values[index] >= values[index - 1] && values[index] > values[index + 1];
            if (!localExtremum) {
                continue;
            }
            int start = Math.max(0, index - neighborhood);
            int end = Math.min(values.length - 1, index + neighborhood);
            double leftReference = Double.POSITIVE_INFINITY;
            double rightReference = leftReference;
            for (int cursor = start; cursor < index; cursor++) {
                leftReference = Math.min(leftReference, values[cursor]);
            }
            for (int cursor = index + 1; cursor <= end; cursor++) {
                rightReference = Math.min(rightReference, values[cursor]);
            }
            if (!Double.isFinite(leftReference) || !Double.isFinite(rightReference)) {
                continue;
            }
            double localBaseline = Math.max(leftReference, rightReference);
            double prominence = values[index] - localBaseline;
            double snr = prominence / Math.max(noise, 1.0e-9d);
            if (prominence >= minProminence && snr >= options.minSignalToNoise) {
                result.add(new Candidate(index, points.get(index).getPixelIndex(), values[index],
                        localBaseline, prominence, snr));
            }
        }
        return result;
    }

    private List<Candidate> selectSeparatedCandidates(List<Candidate> candidates,
                                                       int minDistancePixels,
                                                       int maxPeaks) {
        List<Candidate> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.comparingDouble((Candidate item) -> item.prominence).reversed());
        List<Candidate> selected = new ArrayList<>();
        for (Candidate candidate : ordered) {
            boolean tooClose = false;
            for (Candidate kept : selected) {
                if (Math.abs(candidate.pixelIndex - kept.pixelIndex) < minDistancePixels) {
                    tooClose = true;
                    break;
                }
            }
            if (!tooClose) {
                selected.add(candidate);
            }
            if (selected.size() >= maxPeaks) {
                break;
            }
        }
        selected.sort(Comparator.comparingInt(item -> item.pixelIndex));
        return selected;
    }

    /**
     * 在候选点左右窗口内拟合高斯峰和局部线性基线。窗口局部化很重要：它避免把整个光谱的缓慢背景
     * 误当作某一个峰的形状，同时也使拟合的中心、宽度都保持在像素坐标尺度上。
     */
    private SpectrumAnalysisResponse.Peak fitCandidate(int rank,
                                                        Candidate candidate,
                                                        List<SpectrumExtractionResponse.Point> points,
                                                        double[] values,
                                                        Options options,
                                                        double noise) {
        int start = Math.max(0, candidate.index - options.fitWindowRadius);
        int end = Math.min(values.length - 1, candidate.index + options.fitWindowRadius);
        int edgeCount = Math.max(2, Math.min(4, (end - start + 1) / 3));
        double leftBaseline = mean(values, start, Math.min(end, start + edgeCount - 1));
        double rightBaseline = mean(values, Math.max(start, end - edgeCount + 1), end);
        double xLeft = points.get(start).getPixelIndex() - candidate.pixelIndex;
        double xRight = points.get(end).getPixelIndex() - candidate.pixelIndex;
        double slope = Math.abs(xRight - xLeft) < 1.0e-9d ? 0.0d : (rightBaseline - leftBaseline) / (xRight - xLeft);
        double intercept = (leftBaseline + rightBaseline) / 2.0d;
        double amplitude = candidate.observedIntensity - intercept;
        amplitude = Math.max(amplitude, Math.max(candidate.prominence, 1.0e-9d));
        double sigma = estimateInitialSigma(points, values, candidate, start, end, intercept);
        GaussianFit fit = optimizeGaussian(points, values, candidate, start, end,
                new GaussianParameters(amplitude, 0.0d, sigma, intercept, slope));

        SpectrumAnalysisResponse.Peak peak = new SpectrumAnalysisResponse.Peak();
        peak.setRank(rank);
        peak.setObservedPixelIndex(candidate.pixelIndex);
        peak.setFittedCenterPixel(candidate.pixelIndex + fit.parameters.center);
        peak.setPolarity("POSITIVE");
        peak.setObservedIntensity(candidate.observedIntensity);
        peak.setLocalBaseline(fit.parameters.intercept);
        peak.setAmplitude(fit.parameters.amplitude);
        peak.setFittedPeakIntensity(fit.parameters.intercept + fit.parameters.amplitude);
        peak.setProminence(candidate.prominence);
        peak.setSignalToNoise(Math.abs(fit.parameters.amplitude) / Math.max(noise, 1.0e-9d));
        peak.setSigmaPixels(Math.abs(fit.parameters.sigma));
        peak.setFwhmPixels(Math.abs(fit.parameters.sigma) * FWHM_FACTOR);
        peak.setArea(fit.parameters.amplitude * Math.abs(fit.parameters.sigma) * SQRT_TWO_PI);
        peak.setRSquared(fit.rSquared);
        peak.setFitWindowStart(points.get(start).getPixelIndex());
        peak.setFitWindowEnd(points.get(end).getPixelIndex());
        peak.setFitQuality(isGoodFit(fit, peak, options) ? "GOOD" : "LOW_CONFIDENCE");
        return peak;
    }

    private GaussianFit optimizeGaussian(List<SpectrumExtractionResponse.Point> points,
                                         double[] values,
                                         Candidate candidate,
                                         int start,
                                         int end,
                                         GaussianParameters initial) {
        GaussianParameters current = initial.copy();
        double currentSse = squaredError(points, values, candidate.pixelIndex, start, end, current);
        double lambda = 1.0e-3d;
        double halfWindow = Math.max(1.0d,
                Math.max(Math.abs(points.get(start).getPixelIndex() - candidate.pixelIndex),
                        Math.abs(points.get(end).getPixelIndex() - candidate.pixelIndex)));
        for (int iteration = 0; iteration < 40; iteration++) {
            double[][] normal = new double[5][5];
            double[] right = new double[5];
            for (int index = start; index <= end; index++) {
                double x = points.get(index).getPixelIndex() - candidate.pixelIndex;
                double sigma = Math.max(0.35d, Math.abs(current.sigma));
                double delta = x - current.center;
                double exponential = Math.exp(-0.5d * delta * delta / (sigma * sigma));
                double model = current.amplitude * exponential + current.intercept + current.slope * x;
                double residual = values[index] - model;
                double[] jacobian = new double[]{
                        exponential,
                        current.amplitude * exponential * delta / (sigma * sigma),
                        current.amplitude * exponential * delta * delta / (sigma * sigma * sigma),
                        1.0d,
                        x
                };
                for (int row = 0; row < jacobian.length; row++) {
                    right[row] += jacobian[row] * residual;
                    for (int column = 0; column < jacobian.length; column++) {
                        normal[row][column] += jacobian[row] * jacobian[column];
                    }
                }
            }
            for (int diagonal = 0; diagonal < normal.length; diagonal++) {
                normal[diagonal][diagonal] += lambda * Math.max(normal[diagonal][diagonal], 1.0d);
            }
            double[] change = solveLinearSystem(normal, right);
            if (change == null) {
                break;
            }
            GaussianParameters trial = current.copy();
            trial.amplitude += change[0];
            trial.center = clamp(trial.center + change[1], -halfWindow, halfWindow);
            trial.sigma = clamp(Math.abs(trial.sigma + change[2]), 0.35d, Math.max(0.5d, halfWindow * 1.5d));
            trial.intercept += change[3];
            trial.slope += change[4];
            trial.amplitude = Math.max(0.0d, trial.amplitude);
            double trialSse = squaredError(points, values, candidate.pixelIndex, start, end, trial);
            if (trialSse <= currentSse) {
                current = trial;
                currentSse = trialSse;
                lambda = Math.max(1.0e-8d, lambda * 0.45d);
                if (vectorNorm(change) < 1.0e-5d) {
                    break;
                }
            } else {
                lambda = Math.min(1.0e10d, lambda * 8.0d);
            }
        }
        double mean = mean(values, start, end);
        double total = 0.0d;
        for (int index = start; index <= end; index++) {
            double delta = values[index] - mean;
            total += delta * delta;
        }
        double rSquared = total <= 1.0e-12d ? 1.0d : 1.0d - currentSse / total;
        return new GaussianFit(current, currentSse, rSquared);
    }

    private boolean isGoodFit(GaussianFit fit, SpectrumAnalysisResponse.Peak peak, Options options) {
        if (!Double.isFinite(fit.rSquared) || !Double.isFinite(peak.getSigmaPixels())) {
            return false;
        }
        return peak.getAmplitude() > 0.0d
                && peak.getRSquared() >= 0.85d
                && peak.getSignalToNoise() >= options.minSignalToNoise
                && peak.getSigmaPixels() >= 0.35d
                && peak.getFittedCenterPixel() >= peak.getFitWindowStart()
                && peak.getFittedCenterPixel() <= peak.getFitWindowEnd();
    }

    private double estimateInitialSigma(List<SpectrumExtractionResponse.Point> points,
                                        double[] values,
                                        Candidate candidate,
                                        int start,
                                        int end,
                                        double baseline) {
        double halfLevel = baseline + (candidate.observedIntensity - baseline) / 2.0d;
        int left = candidate.index;
        while (left > start && values[left] > halfLevel) {
            left--;
        }
        int right = candidate.index;
        while (right < end && values[right] > halfLevel) {
            right++;
        }
        if (left == start || right == end) {
            return Math.max(0.8d, Math.min(3.0d, (points.get(end).getPixelIndex() - points.get(start).getPixelIndex()) / 5.0d));
        }
        double leftX = interpolateX(points.get(left).getPixelIndex(), values[left],
                points.get(left + 1).getPixelIndex(), values[left + 1], halfLevel);
        double rightX = interpolateX(points.get(right - 1).getPixelIndex(), values[right - 1],
                points.get(right).getPixelIndex(), values[right], halfLevel);
        return clamp(Math.abs(rightX - leftX) / FWHM_FACTOR, 0.35d,
                Math.max(0.6d, (points.get(end).getPixelIndex() - points.get(start).getPixelIndex()) / 2.0d));
    }

    private double interpolateX(double x1, double y1, double x2, double y2, double targetY) {
        if (Math.abs(y2 - y1) < 1.0e-12d) {
            return (x1 + x2) / 2.0d;
        }
        return x1 + (targetY - y1) * (x2 - x1) / (y2 - y1);
    }

    private double squaredError(List<SpectrumExtractionResponse.Point> points,
                                double[] values,
                                int observedPixel,
                                int start,
                                int end,
                                GaussianParameters parameters) {
        double error = 0.0d;
        double sigma = Math.max(0.35d, Math.abs(parameters.sigma));
        for (int index = start; index <= end; index++) {
            double x = points.get(index).getPixelIndex() - observedPixel;
            double delta = x - parameters.center;
            double model = parameters.amplitude * Math.exp(-0.5d * delta * delta / (sigma * sigma))
                    + parameters.intercept + parameters.slope * x;
            double residual = values[index] - model;
            error += residual * residual;
        }
        return error;
    }

    /** 用带主元选取的高斯消元解5×5正规方程；奇异时返回null而不是报告不可信参数。 */
    private double[] solveLinearSystem(double[][] matrix, double[] vector) {
        int size = vector.length;
        double[][] augmented = new double[size][size + 1];
        for (int row = 0; row < size; row++) {
            System.arraycopy(matrix[row], 0, augmented[row], 0, size);
            augmented[row][size] = vector[row];
        }
        for (int pivot = 0; pivot < size; pivot++) {
            int best = pivot;
            for (int row = pivot + 1; row < size; row++) {
                if (Math.abs(augmented[row][pivot]) > Math.abs(augmented[best][pivot])) {
                    best = row;
                }
            }
            if (Math.abs(augmented[best][pivot]) < 1.0e-12d) {
                return null;
            }
            double[] temp = augmented[pivot];
            augmented[pivot] = augmented[best];
            augmented[best] = temp;
            double divisor = augmented[pivot][pivot];
            for (int column = pivot; column <= size; column++) {
                augmented[pivot][column] /= divisor;
            }
            for (int row = 0; row < size; row++) {
                if (row == pivot) {
                    continue;
                }
                double multiplier = augmented[row][pivot];
                for (int column = pivot; column <= size; column++) {
                    augmented[row][column] -= multiplier * augmented[pivot][column];
                }
            }
        }
        double[] result = new double[size];
        for (int row = 0; row < size; row++) {
            result[row] = augmented[row][size];
        }
        return result;
    }

    private SpectrumAnalysisResponse buildResponse(Long id,
                                                   SpectrumExtractionResponse extracted,
                                                   InputCurve input,
                                                   double dynamicRange,
                                                   double noise,
                                                   int candidateCount,
                                                   List<SpectrumAnalysisResponse.Peak> peaks,
                                                   Map<String, Object> parameters,
                                                   Map<String, Object> details,
                                                   String summary,
                                                   OffsetDateTime createdAt) {
        SpectrumAnalysisResponse response = new SpectrumAnalysisResponse();
        response.setId(id);
        response.setSpectrumId(extracted.getId());
        response.setPreprocessingId(input.preprocessingId);
        response.setImageId(extracted.getImageId());
        response.setCaptureId(extracted.getCaptureId());
        response.setSource(input.source);
        response.setSourceDescription("PREPROCESSED".equals(input.source)
                ? "已使用保存的一维光谱预处理结果；横坐标仍为像素位置"
                : "已使用原始一维光谱提取结果；横坐标为像素位置");
        response.setPointCount(input.points.size());
        response.setDynamicRange(dynamicRange);
        response.setNoiseEstimate(noise);
        response.setCandidatePeakCount(candidateCount);
        response.setPeakCount(peaks.size());
        response.setAnalysisParameters(parameters);
        response.setPeaks(peaks);
        response.setAlgorithmVersion(ALGORITHM_VERSION);
        response.setSummaryMessage(summary);
        response.setDetails(details);
        response.setCreatedAt(createdAt == null ? OffsetDateTime.now(ZoneOffset.UTC) : createdAt);
        return response;
    }

    private SpectrumAnalysisResponse mapResponse(ResultSet resultSet) throws SQLException {
        SpectrumAnalysisResponse response = new SpectrumAnalysisResponse();
        response.setId(resultSet.getLong("id"));
        response.setSpectrumId(resultSet.getLong("spectrum_id"));
        Object preprocessingId = resultSet.getObject("preprocessing_id");
        response.setPreprocessingId(preprocessingId == null ? null : ((Number) preprocessingId).longValue());
        response.setImageId(resultSet.getLong("image_id"));
        response.setCaptureId(resultSet.getLong("capture_id"));
        response.setSource(resultSet.getString("source"));
        response.setSourceDescription("PREPROCESSED".equals(response.getSource())
                ? "已使用保存的一维光谱预处理结果；横坐标仍为像素位置"
                : "已使用原始一维光谱提取结果；横坐标为像素位置");
        response.setPointCount(resultSet.getInt("point_count"));
        response.setDynamicRange(numberValue(resultSet, "dynamic_range"));
        response.setNoiseEstimate(numberValue(resultSet, "noise_estimate"));
        response.setCandidatePeakCount(resultSet.getInt("candidate_peak_count"));
        response.setPeakCount(resultSet.getInt("peak_count"));
        response.setAnalysisParameters(readMap(resultSet.getString("analysis_parameters_json")));
        response.setPeaks(readPeaks(resultSet.getString("peak_results_json")));
        response.setAlgorithmVersion(resultSet.getString("algorithm_version"));
        response.setSummaryMessage(resultSet.getString("summary_message"));
        response.setDetails(readMap(resultSet.getString("details_json")));
        response.setCreatedAt(resultSet.getObject("created_at", OffsetDateTime.class));
        return response;
    }

    private List<SpectrumExtractionResponse.Point> sanitizePoints(List<SpectrumExtractionResponse.Point> points) {
        if (points == null) {
            return Collections.emptyList();
        }
        List<SpectrumExtractionResponse.Point> result = new ArrayList<>();
        for (SpectrumExtractionResponse.Point point : points) {
            if (point != null && point.getPixelIndex() != null && point.getIntensity() != null
                    && Double.isFinite(point.getIntensity())) {
                result.add(point);
            }
        }
        result.sort(Comparator.comparingInt(SpectrumExtractionResponse.Point::getPixelIndex));
        return result;
    }

    private double[] toValues(List<SpectrumExtractionResponse.Point> points) {
        double[] values = new double[points.size()];
        for (int index = 0; index < points.size(); index++) {
            values[index] = points.get(index).getIntensity();
        }
        return values;
    }

    private double estimateRobustNoise(double[] values, double dynamicRange) {
        if (values.length < 3) {
            return Math.max(dynamicRange * 1.0e-6d, 1.0e-9d);
        }
        double[] differences = new double[values.length - 1];
        for (int index = 1; index < values.length; index++) {
            differences[index - 1] = values[index] - values[index - 1];
        }
        double median = median(differences);
        double[] deviations = new double[differences.length];
        for (int index = 0; index < differences.length; index++) {
            deviations[index] = Math.abs(differences[index] - median);
        }
        return Math.max(1.4826d * median(deviations) / Math.sqrt(2.0d),
                Math.max(dynamicRange * 1.0e-8d, 1.0e-9d));
    }

    private double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int middle = sorted.length / 2;
        return sorted.length % 2 == 0 ? (sorted[middle - 1] + sorted[middle]) / 2.0d : sorted[middle];
    }

    private double range(double[] values) {
        if (values.length == 0) {
            return 0.0d;
        }
        double min = values[0];
        double max = values[0];
        for (double value : values) {
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        return max - min;
    }

    private double mean(double[] values, int start, int end) {
        double sum = 0.0d;
        int count = 0;
        for (int index = start; index <= end; index++) {
            sum += values[index];
            count++;
        }
        return count == 0 ? 0.0d : sum / count;
    }

    private double vectorNorm(double[] values) {
        double sum = 0.0d;
        for (double value : values) {
            sum += value * value;
        }
        return Math.sqrt(sum);
    }

    private Map<String, Integer> countFitQuality(List<SpectrumAnalysisResponse.Peak> peaks) {
        int good = 0;
        for (SpectrumAnalysisResponse.Peak peak : peaks) {
            if ("GOOD".equals(peak.getFitQuality())) {
                good++;
            }
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("good", good);
        result.put("lowConfidence", peaks.size() - good);
        return result;
    }

    private String buildSummary(InputCurve input,
                                int candidateCount,
                                List<SpectrumAnalysisResponse.Peak> peaks) {
        int good = 0;
        for (SpectrumAnalysisResponse.Peak peak : peaks) {
            if ("GOOD".equals(peak.getFitQuality())) {
                good++;
            }
        }
        return String.format(Locale.ROOT,
                "基于%s曲线检测到%d个正向峰候选，保留并拟合%d个，其中%d个拟合可信；全部位置和宽度均为pixelIndex单位。",
                "PREPROCESSED".equals(input.source) ? "预处理" : "原始一维", candidateCount, peaks.size(), good);
    }

    private Map<String, Object> readMap(String value) {
        try {
            return value == null || value.trim().isEmpty() ? new LinkedHashMap<String, Object>()
                    : objectMapper.readValue(value, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("读取光谱分析参数失败", exception);
        }
    }

    private List<SpectrumAnalysisResponse.Peak> readPeaks(String value) {
        try {
            return value == null || value.trim().isEmpty() ? new ArrayList<SpectrumAnalysisResponse.Peak>()
                    : objectMapper.readValue(value, PEAK_LIST_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("读取光谱拟合峰表失败", exception);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("保存光谱分析JSON失败", exception);
        }
    }

    private Double numberValue(ResultSet resultSet, String column) throws SQLException {
        Object value = resultSet.getObject(column);
        return value == null ? null : ((Number) value).doubleValue();
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class InputCurve {
        private final String source;
        private final Long preprocessingId;
        private final List<SpectrumExtractionResponse.Point> points;

        private InputCurve(String source, Long preprocessingId, List<SpectrumExtractionResponse.Point> points) {
            this.source = source;
            this.preprocessingId = preprocessingId;
            this.points = points;
        }
    }

    private static final class Candidate {
        private final int index;
        private final int pixelIndex;
        private final double observedIntensity;
        private final double localBaseline;
        private final double prominence;
        private final double signalToNoise;

        private Candidate(int index, int pixelIndex, double observedIntensity,
                          double localBaseline, double prominence, double signalToNoise) {
            this.index = index;
            this.pixelIndex = pixelIndex;
            this.observedIntensity = observedIntensity;
            this.localBaseline = localBaseline;
            this.prominence = prominence;
            this.signalToNoise = signalToNoise;
        }
    }

    private static final class GaussianParameters {
        private double amplitude;
        private double center;
        private double sigma;
        private double intercept;
        private double slope;

        private GaussianParameters(double amplitude, double center, double sigma, double intercept, double slope) {
            this.amplitude = amplitude;
            this.center = center;
            this.sigma = sigma;
            this.intercept = intercept;
            this.slope = slope;
        }

        private GaussianParameters copy() {
            return new GaussianParameters(amplitude, center, sigma, intercept, slope);
        }
    }

    private static final class GaussianFit {
        private final GaussianParameters parameters;
        private final double sse;
        private final double rSquared;

        private GaussianFit(GaussianParameters parameters, double sse, double rSquared) {
            this.parameters = parameters;
            this.sse = sse;
            this.rSquared = rSquared;
        }
    }

    private static final class SavedAnalysis {
        private final Long id;
        private final OffsetDateTime createdAt;

        private SavedAnalysis(Long id, OffsetDateTime createdAt) {
            this.id = id;
            this.createdAt = createdAt;
        }
    }

    private static final class Options {
        private final double minProminenceRatio;
        private final double minSignalToNoise;
        private final int minDistancePixels;
        private final int fitWindowRadius;
        private final int maxPeaks;

        private Options(double minProminenceRatio, double minSignalToNoise,
                        int minDistancePixels, int fitWindowRadius, int maxPeaks) {
            this.minProminenceRatio = minProminenceRatio;
            this.minSignalToNoise = minSignalToNoise;
            this.minDistancePixels = minDistancePixels;
            this.fitWindowRadius = fitWindowRadius;
            this.maxPeaks = maxPeaks;
        }

        private static Options from(SpectrumAnalysisRequest request, int pointCount) {
            return new Options(
                    clamp(request == null || request.getMinProminenceRatio() == null
                            ? 0.04d : request.getMinProminenceRatio(), 0.001d, 0.8d),
                    clamp(request == null || request.getMinSignalToNoise() == null
                            ? 4.0d : request.getMinSignalToNoise(), 1.0d, 100.0d),
                    clamp(request == null || request.getMinDistancePixels() == null
                            ? 8 : request.getMinDistancePixels(), 1, Math.max(1, pointCount / 3)),
                    clamp(request == null || request.getFitWindowRadius() == null
                            ? 12 : request.getFitWindowRadius(), 3, Math.max(3, pointCount / 3)),
                    clamp(request == null || request.getMaxPeaks() == null
                            ? 20 : request.getMaxPeaks(), 1, 200));
        }

        private Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            // 固定来源标记，用于区分历史负向结果，不再作为可选分析参数。
            result.put("peakPolarity", "POSITIVE");
            result.put("minProminenceRatio", minProminenceRatio);
            result.put("minSignalToNoise", minSignalToNoise);
            result.put("minDistancePixels", minDistancePixels);
            result.put("fitWindowRadius", fitWindowRadius);
            result.put("maxPeaks", maxPeaks);
            return result;
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }

        private static double clamp(double value, double min, double max) {
            return Math.max(min, Math.min(max, value));
        }
    }
}
