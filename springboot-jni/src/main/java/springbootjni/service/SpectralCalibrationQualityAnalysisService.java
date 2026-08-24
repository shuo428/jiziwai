package springbootjni.service;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 暗场/平场校准样本专用质量评价。
 *
 * <p>这和普通光谱图像质量分析不是同一个问题：普通图像质量分析关注“这张图能不能进入光谱提取”，
 * 而校准样本质量分析关注“这组暗场/平场能不能作为后续所有图像的校准基准”。因此这里把暗场和平场
 * 分开评价：</p>
 *
 * <ul>
 *     <li>暗场：重点看遮光是否正常、暗电平是否过高、噪声是否过大、是否有热像素/异常行列、
 *     多帧之间是否稳定；</li>
 *     <li>平场：重点看亮度是否落在合理区间、是否饱和、是否太暗、空间均匀性/PRNU 是否过差、
 *     多帧之间是否稳定。</li>
 * </ul>
 *
 * <p>注意：稳定坏点、异常行、异常列仍然来自多帧投票缺陷地图。它们只能说明“固定缺陷”情况，
 * 不能代表暗场/平场整体质量，所以本服务会额外计算专用指标并生成 PASS/WARNING/FAIL。</p>
 */
@Service
public class SpectralCalibrationQualityAnalysisService {
    private static final int SENSOR_MAX_DN = 4095;
    private static final String ANALYSIS_VERSION = "calibration-quality-v1";

    /** 暗场均值超过该阈值给 WARNING，常见原因是漏光、遮光不严或暗电流偏高。 */
    @Value("${spectral.calibration.dark.mean-warning-dn:64.0}")
    private double darkMeanWarningDn;

    /** 暗场均值超过该阈值给 FAIL，通常不建议作为暗场参考。 */
    @Value("${spectral.calibration.dark.mean-fail-dn:256.0}")
    private double darkMeanFailDn;

    /** 暗场鲁棒噪声超过该阈值给 WARNING。鲁棒噪声使用 MAD 换算得到，抗极端坏点干扰。 */
    @Value("${spectral.calibration.dark.noise-warning-dn:16.0}")
    private double darkNoiseWarningDn;

    /** 暗场鲁棒噪声超过该阈值给 FAIL。 */
    @Value("${spectral.calibration.dark.noise-fail-dn:64.0}")
    private double darkNoiseFailDn;

    /** 暗场高亮异常像素比例超过该值给 WARNING。 */
    @Value("${spectral.calibration.dark.bright-warning-ratio:0.001}")
    private double darkBrightWarningRatio;

    /** 暗场高亮异常像素比例超过该值给 FAIL。 */
    @Value("${spectral.calibration.dark.bright-fail-ratio:0.01}")
    private double darkBrightFailRatio;

    /** 暗场多帧均值波动超过该 DN 给 WARNING。 */
    @Value("${spectral.calibration.dark.temporal-std-warning-dn:8.0}")
    private double darkTemporalStdWarningDn;

    /** 暗场多帧均值波动超过该 DN 给 FAIL。 */
    @Value("${spectral.calibration.dark.temporal-std-fail-dn:32.0}")
    private double darkTemporalStdFailDn;

    /** 平场均值低于满量程该比例给 WARNING。 */
    @Value("${spectral.calibration.flat.mean-low-warning-ratio:0.30}")
    private double flatMeanLowWarningRatio;

    /** 平场均值低于满量程该比例给 FAIL。 */
    @Value("${spectral.calibration.flat.mean-low-fail-ratio:0.15}")
    private double flatMeanLowFailRatio;

    /** 平场均值高于满量程该比例给 WARNING。 */
    @Value("${spectral.calibration.flat.mean-high-warning-ratio:0.75}")
    private double flatMeanHighWarningRatio;

    /** 平场均值高于满量程该比例给 FAIL。 */
    @Value("${spectral.calibration.flat.mean-high-fail-ratio:0.90}")
    private double flatMeanHighFailRatio;

    /** 平场饱和比例超过该值给 WARNING。 */
    @Value("${spectral.calibration.flat.saturation-warning-ratio:0.0001}")
    private double flatSaturationWarningRatio;

    /** 平场饱和比例超过该值给 FAIL。 */
    @Value("${spectral.calibration.flat.saturation-fail-ratio:0.001}")
    private double flatSaturationFailRatio;

    /** 平场接近黑场比例超过该值给 WARNING。 */
    @Value("${spectral.calibration.flat.black-warning-ratio:0.001}")
    private double flatBlackWarningRatio;

    /** 平场接近黑场比例超过该值给 FAIL。 */
    @Value("${spectral.calibration.flat.black-fail-ratio:0.01}")
    private double flatBlackFailRatio;

    /** 平场 P95-P5 相对中位数的比例超过该值给 WARNING，提示光源/光路不均匀。 */
    @Value("${spectral.calibration.flat.uniformity-warning-ratio:0.35}")
    private double flatUniformityWarningRatio;

    /** 平场 P95-P5 相对中位数的比例超过该值给 FAIL。 */
    @Value("${spectral.calibration.flat.uniformity-fail-ratio:0.70}")
    private double flatUniformityFailRatio;

    /** 平场 PRNU 近似值超过该值给 WARNING。这里用 robust sigma / median 估算。 */
    @Value("${spectral.calibration.flat.prnu-warning-ratio:0.08}")
    private double flatPrnuWarningRatio;

    /** 平场 PRNU 近似值超过该值给 FAIL。 */
    @Value("${spectral.calibration.flat.prnu-fail-ratio:0.20}")
    private double flatPrnuFailRatio;

    /** 平场多帧均值变异系数超过该值给 WARNING。 */
    @Value("${spectral.calibration.flat.temporal-cv-warning-ratio:0.02}")
    private double flatTemporalCvWarningRatio;

    /** 平场多帧均值变异系数超过该值给 FAIL。 */
    @Value("${spectral.calibration.flat.temporal-cv-fail-ratio:0.08}")
    private double flatTemporalCvFailRatio;

    /** 稳定坏点超过该数量给 WARNING；该值对应当前基础质量分析中使用的工程参考。 */
    @Value("${spectral.calibration.defect.bad-pixel-warning-count:60}")
    private int defectBadPixelWarningCount;

    /** 稳定坏点超过该数量给 FAIL。 */
    @Value("${spectral.calibration.defect.bad-pixel-fail-count:500}")
    private int defectBadPixelFailCount;

    /** 稳定异常行/列超过该总数给 FAIL。 */
    @Value("${spectral.calibration.defect.bad-line-fail-count:5}")
    private int defectBadLineFailCount;

    /**
     * 对一组已经用于生成校准参考图的多帧样本做专用质量评价。
     *
     * @param calibrationType DARK 或 FLAT
     * @param reference       多帧中值参考图，也就是最终用于校准的那张参考 RAW
     * @param defectResult    多帧投票得到的稳定缺陷结果
     */
    public CalibrationQualityResult analyze(String calibrationType,
                                            int width,
                                            int height,
                                            List<short[]> frames,
                                            short[] reference,
                                            SpectralMultiFrameQualityAnalysisService.MultiFrameResult defectResult) {
        validate(width, height, frames, reference);
        String type = normalizeType(calibrationType);
        BasicStats referenceStats = basicStats(reference);
        FrameStats frameStats = frameStats(frames);
        if ("DARK".equals(type)) {
            return analyzeDark(width, height, referenceStats, frameStats, defectResult);
        }
        return analyzeFlat(width, height, referenceStats, frameStats, defectResult);
    }

    private CalibrationQualityResult analyzeDark(int width,
                                                 int height,
                                                 BasicStats stats,
                                                 FrameStats frameStats,
                                                 SpectralMultiFrameQualityAnalysisService.MultiFrameResult defectResult) {
        List<String> reasons = new ArrayList<>();
        boolean fail = false;
        boolean warning = false;

        double brightThresholdDn = Math.max(64.0d, stats.median + Math.max(32.0d, stats.robustSigma * 8.0d));
        double brightRatio = ratioAtLeast(stats.sortedValues, brightThresholdDn);

        if (stats.mean >= darkMeanFailDn) {
            fail = true;
            reasons.add("暗场均值过高，可能存在明显漏光、遮光不严或暗电流异常");
        } else if (stats.mean >= darkMeanWarningDn) {
            warning = true;
            reasons.add("暗场均值偏高，建议检查遮光条件和曝光/增益设置");
        }

        if (stats.robustSigma >= darkNoiseFailDn) {
            fail = true;
            reasons.add("暗场鲁棒噪声过大，扣暗场时会把噪声带入正式图像");
        } else if (stats.robustSigma >= darkNoiseWarningDn) {
            warning = true;
            reasons.add("暗场噪声偏大，建议重新采集更多帧或检查传感器工作状态");
        }

        if (brightRatio >= darkBrightFailRatio) {
            fail = true;
            reasons.add("暗场高亮异常区域比例过高，可能存在漏光、热像素聚集或读出异常");
        } else if (brightRatio >= darkBrightWarningRatio) {
            warning = true;
            reasons.add("暗场存在一定比例高亮异常像素，建议关注热像素或局部漏光");
        }

        if (stats.saturationRatio > 0.0d) {
            fail = true;
            reasons.add("暗场出现饱和像素，无光条件下不应接近 ADC 满量程");
        }

        DefectDecision defectDecision = evaluateDefects(defectResult, reasons);
        fail = fail || defectDecision.fail;
        warning = warning || defectDecision.warning;

        if (frameStats.meanStd >= darkTemporalStdFailDn) {
            fail = true;
            reasons.add("暗场多帧均值波动过大，采集环境或传感器状态不稳定");
        } else if (frameStats.meanStd >= darkTemporalStdWarningDn) {
            warning = true;
            reasons.add("暗场多帧均值有波动，建议延长稳定时间后重新采集");
        }

        String status = decideStatus(fail, warning);
        Map<String, Object> metrics = commonMetricMap(width, height, stats, frameStats, defectResult);
        metrics.put("brightThresholdDn", round(brightThresholdDn));
        metrics.put("brightPixelRatio", brightRatio);

        Map<String, Object> thresholds = new LinkedHashMap<>();
        thresholds.put("darkMeanWarningDn", darkMeanWarningDn);
        thresholds.put("darkMeanFailDn", darkMeanFailDn);
        thresholds.put("darkNoiseWarningDn", darkNoiseWarningDn);
        thresholds.put("darkNoiseFailDn", darkNoiseFailDn);
        thresholds.put("darkBrightWarningRatio", darkBrightWarningRatio);
        thresholds.put("darkBrightFailRatio", darkBrightFailRatio);
        thresholds.put("darkTemporalStdWarningDn", darkTemporalStdWarningDn);
        thresholds.put("darkTemporalStdFailDn", darkTemporalStdFailDn);
        putDefectThresholds(thresholds);

        return result("DARK", status, reasons, metrics, thresholds);
    }

    private CalibrationQualityResult analyzeFlat(int width,
                                                 int height,
                                                 BasicStats stats,
                                                 FrameStats frameStats,
                                                 SpectralMultiFrameQualityAnalysisService.MultiFrameResult defectResult) {
        List<String> reasons = new ArrayList<>();
        boolean fail = false;
        boolean warning = false;

        double meanRatio = stats.mean / SENSOR_MAX_DN;
        double uniformityRatio = (stats.p95 - stats.p05) / Math.max(1.0d, stats.median);
        double prnuRatio = stats.robustSigma / Math.max(1.0d, stats.median);
        double temporalCv = frameStats.meanStd / Math.max(1.0d, frameStats.meanOfMeans);

        if (meanRatio <= flatMeanLowFailRatio) {
            fail = true;
            reasons.add("平场整体太暗，信噪比不足，生成的平场校正系数不可靠");
        } else if (meanRatio < flatMeanLowWarningRatio) {
            warning = true;
            reasons.add("平场亮度偏低，建议提高均匀光源亮度或调整曝光");
        }

        if (meanRatio >= flatMeanHighFailRatio) {
            fail = true;
            reasons.add("平场整体过亮，接近 ADC 满量程，响应线性可能失真");
        } else if (meanRatio > flatMeanHighWarningRatio) {
            warning = true;
            reasons.add("平场亮度偏高，建议降低曝光或光源强度，避免局部饱和");
        }

        if (stats.saturationRatio >= flatSaturationFailRatio) {
            fail = true;
            reasons.add("平场饱和像素比例过高，后续除法校正会失真");
        } else if (stats.saturationRatio >= flatSaturationWarningRatio) {
            warning = true;
            reasons.add("平场存在少量饱和像素，建议降低曝光或光源强度");
        }

        if (stats.blackRatio >= flatBlackFailRatio) {
            fail = true;
            reasons.add("平场接近黑场的像素比例过高，可能存在遮挡、坏区域或照明不足");
        } else if (stats.blackRatio >= flatBlackWarningRatio) {
            warning = true;
            reasons.add("平场存在少量接近黑场像素，建议检查遮挡和光路污染");
        }

        if (uniformityRatio >= flatUniformityFailRatio) {
            fail = true;
            reasons.add("平场空间不均匀性过强，可能有严重阴影、光斑、脏污或光源不均匀");
        } else if (uniformityRatio >= flatUniformityWarningRatio) {
            warning = true;
            reasons.add("平场空间不均匀性偏大，建议检查均匀光源和光路");
        }

        if (prnuRatio >= flatPrnuFailRatio) {
            fail = true;
            reasons.add("平场 PRNU 估计值过高，像素响应差异或照明纹理过强");
        } else if (prnuRatio >= flatPrnuWarningRatio) {
            warning = true;
            reasons.add("平场 PRNU 估计值偏高，建议检查光源均匀性和传感器响应");
        }

        DefectDecision defectDecision = evaluateDefects(defectResult, reasons);
        fail = fail || defectDecision.fail;
        warning = warning || defectDecision.warning;

        if (temporalCv >= flatTemporalCvFailRatio) {
            fail = true;
            reasons.add("平场多帧亮度波动过大，可能存在光源闪烁或采集状态不稳定");
        } else if (temporalCv >= flatTemporalCvWarningRatio) {
            warning = true;
            reasons.add("平场多帧亮度存在波动，建议使用更稳定的均匀光源");
        }

        String status = decideStatus(fail, warning);
        Map<String, Object> metrics = commonMetricMap(width, height, stats, frameStats, defectResult);
        metrics.put("meanRatio", meanRatio);
        metrics.put("uniformityRatio", uniformityRatio);
        metrics.put("prnuRatio", prnuRatio);
        metrics.put("temporalCv", temporalCv);

        Map<String, Object> thresholds = new LinkedHashMap<>();
        thresholds.put("flatMeanLowWarningRatio", flatMeanLowWarningRatio);
        thresholds.put("flatMeanLowFailRatio", flatMeanLowFailRatio);
        thresholds.put("flatMeanHighWarningRatio", flatMeanHighWarningRatio);
        thresholds.put("flatMeanHighFailRatio", flatMeanHighFailRatio);
        thresholds.put("flatSaturationWarningRatio", flatSaturationWarningRatio);
        thresholds.put("flatSaturationFailRatio", flatSaturationFailRatio);
        thresholds.put("flatBlackWarningRatio", flatBlackWarningRatio);
        thresholds.put("flatBlackFailRatio", flatBlackFailRatio);
        thresholds.put("flatUniformityWarningRatio", flatUniformityWarningRatio);
        thresholds.put("flatUniformityFailRatio", flatUniformityFailRatio);
        thresholds.put("flatPrnuWarningRatio", flatPrnuWarningRatio);
        thresholds.put("flatPrnuFailRatio", flatPrnuFailRatio);
        thresholds.put("flatTemporalCvWarningRatio", flatTemporalCvWarningRatio);
        thresholds.put("flatTemporalCvFailRatio", flatTemporalCvFailRatio);
        putDefectThresholds(thresholds);

        return result("FLAT", status, reasons, metrics, thresholds);
    }

    private CalibrationQualityResult result(String type,
                                            String status,
                                            List<String> reasons,
                                            Map<String, Object> metrics,
                                            Map<String, Object> thresholds) {
        List<String> normalizedReasons = reasons.isEmpty()
                ? Collections.singletonList("校准样本专用质量指标均在当前工程阈值内")
                : reasons;
        String summary = "PASS".equals(status)
                ? ("DARK".equals(type) ? "暗场校准样本质量通过" : "平场校准样本质量通过")
                : ("DARK".equals(type) ? "暗场校准样本质量" : "平场校准样本质量") + status
                    + "：" + String.join("；", normalizedReasons);
        return new CalibrationQualityResult(
                ANALYSIS_VERSION,
                type,
                status,
                !"FAIL".equals(status),
                summary,
                normalizedReasons,
                metrics,
                thresholds);
    }

    private Map<String, Object> commonMetricMap(int width,
                                                int height,
                                                BasicStats stats,
                                                FrameStats frameStats,
                                                SpectralMultiFrameQualityAnalysisService.MultiFrameResult defectResult) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("width", width);
        metrics.put("height", height);
        metrics.put("frameCount", frameStats.frameCount);
        metrics.put("minDn", stats.min);
        metrics.put("maxDn", stats.max);
        metrics.put("meanDn", round(stats.mean));
        metrics.put("medianDn", round(stats.median));
        metrics.put("stddevDn", round(stats.stddev));
        metrics.put("madDn", round(stats.mad));
        metrics.put("robustSigmaDn", round(stats.robustSigma));
        metrics.put("p05Dn", round(stats.p05));
        metrics.put("p95Dn", round(stats.p95));
        metrics.put("blackPixelRatio", stats.blackRatio);
        metrics.put("saturationPixelRatio", stats.saturationRatio);
        metrics.put("frameMeanStdDn", round(frameStats.meanStd));
        metrics.put("frameMeanRangeDn", round(frameStats.meanRange));
        metrics.put("defectMapBadPixelCount", defectResult == null ? 0 : defectResult.getBadPixelIndexes().size());
        metrics.put("defectMapAbnormalRowCount", defectResult == null ? 0 : defectResult.getAbnormalRows().size());
        metrics.put("defectMapAbnormalColumnCount", defectResult == null ? 0 : defectResult.getAbnormalColumns().size());
        metrics.put("defectMapVoteRatio", defectResult == null ? 0.6d : defectResult.getVoteRatio());
        return metrics;
    }

    private DefectDecision evaluateDefects(
            SpectralMultiFrameQualityAnalysisService.MultiFrameResult defectResult,
            List<String> reasons) {
        if (defectResult == null) {
            return new DefectDecision(false, false);
        }
        int badPixelCount = defectResult.getBadPixelIndexes().size();
        int badLineCount = defectResult.getAbnormalRows().size() + defectResult.getAbnormalColumns().size();
        boolean fail = false;
        boolean warning = false;
        if (badPixelCount >= defectBadPixelFailCount) {
            fail = true;
            reasons.add("稳定坏点数量过多，缺陷地图修复压力较大");
        } else if (badPixelCount >= defectBadPixelWarningCount) {
            warning = true;
            reasons.add("检测到一定数量稳定坏点，启用稳定缺陷修复时会进行修正");
        }
        if (badLineCount >= defectBadLineFailCount) {
            fail = true;
            reasons.add("稳定异常行/列数量过多，可能存在读出链路或传感器缺陷");
        } else if (badLineCount > 0) {
            warning = true;
            reasons.add("检测到稳定异常行/列，建议确认是否为传感器或读出链路固定缺陷");
        }
        return new DefectDecision(fail, warning);
    }

    private void putDefectThresholds(Map<String, Object> thresholds) {
        thresholds.put("defectBadPixelWarningCount", defectBadPixelWarningCount);
        thresholds.put("defectBadPixelFailCount", defectBadPixelFailCount);
        thresholds.put("defectBadLineFailCount", defectBadLineFailCount);
    }

    private BasicStats basicStats(short[] pixels) {
        int[] values = new int[pixels.length];
        long sum = 0L;
        int min = SENSOR_MAX_DN;
        int max = 0;
        int blackCount = 0;
        int saturationCount = 0;
        for (int index = 0; index < pixels.length; index++) {
            int value = pixels[index] & SENSOR_MAX_DN;
            values[index] = value;
            sum += value;
            min = Math.min(min, value);
            max = Math.max(max, value);
            if (value <= 16) {
                blackCount++;
            }
            if (value >= 4080) {
                saturationCount++;
            }
        }
        Arrays.sort(values);
        double mean = (double) sum / pixels.length;
        double median = percentileFromSorted(values, 0.50d);
        double p05 = percentileFromSorted(values, 0.05d);
        double p95 = percentileFromSorted(values, 0.95d);

        double varianceSum = 0.0d;
        int[] deviations = new int[pixels.length];
        for (int index = 0; index < pixels.length; index++) {
            int value = pixels[index] & SENSOR_MAX_DN;
            double diff = value - mean;
            varianceSum += diff * diff;
            deviations[index] = (int) Math.round(Math.abs(value - median));
        }
        Arrays.sort(deviations);
        double mad = percentileFromSorted(deviations, 0.50d);
        return new BasicStats(
                values,
                min,
                max,
                mean,
                Math.sqrt(varianceSum / pixels.length),
                median,
                mad,
                mad * 1.4826d,
                p05,
                p95,
                (double) blackCount / pixels.length,
                (double) saturationCount / pixels.length);
    }

    private FrameStats frameStats(List<short[]> frames) {
        double[] means = new double[frames.size()];
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        double sum = 0.0d;
        for (int index = 0; index < frames.size(); index++) {
            short[] frame = frames.get(index);
            long frameSum = 0L;
            for (short pixel : frame) {
                frameSum += pixel & SENSOR_MAX_DN;
            }
            double mean = (double) frameSum / frame.length;
            means[index] = mean;
            min = Math.min(min, mean);
            max = Math.max(max, mean);
            sum += mean;
        }
        double meanOfMeans = sum / frames.size();
        double varianceSum = 0.0d;
        for (double mean : means) {
            double diff = mean - meanOfMeans;
            varianceSum += diff * diff;
        }
        return new FrameStats(
                frames.size(),
                meanOfMeans,
                Math.sqrt(varianceSum / frames.size()),
                max - min);
    }

    private double ratioAtLeast(int[] sortedValues, double threshold) {
        int first = lowerBound(sortedValues, (int) Math.ceil(threshold));
        return (double) (sortedValues.length - first) / sortedValues.length;
    }

    private int lowerBound(int[] sortedValues, int threshold) {
        int left = 0;
        int right = sortedValues.length;
        while (left < right) {
            int middle = (left + right) >>> 1;
            if (sortedValues[middle] < threshold) {
                left = middle + 1;
            } else {
                right = middle;
            }
        }
        return left;
    }

    private double percentileFromSorted(int[] sortedValues, double percentile) {
        if (sortedValues.length == 0) {
            return 0.0d;
        }
        double position = percentile * (sortedValues.length - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) {
            return sortedValues[lower];
        }
        double weight = position - lower;
        return sortedValues[lower] * (1.0d - weight) + sortedValues[upper] * weight;
    }

    private String decideStatus(boolean fail, boolean warning) {
        if (fail) {
            return "FAIL";
        }
        return warning ? "WARNING" : "PASS";
    }

    private String normalizeType(String value) {
        String type = value == null ? "" : value.trim().toUpperCase();
        if ("HDR_DARK".equals(type)) {
            return "DARK";
        }
        if ("HDR_FLAT".equals(type)) {
            return "FLAT";
        }
        if (!"DARK".equals(type) && !"FLAT".equals(type)) {
            throw new IllegalArgumentException("校准类型必须是DARK、FLAT、HDR_DARK或HDR_FLAT");
        }
        return type;
    }

    private void validate(int width, int height, List<short[]> frames, short[] reference) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("校准图像宽高必须为正数");
        }
        int expectedPixels = Math.multiplyExact(width, height);
        if (reference == null || reference.length != expectedPixels) {
            throw new IllegalArgumentException("校准参考图尺寸不一致");
        }
        if (frames == null || frames.size() < 2) {
            throw new IllegalArgumentException("校准质量评价至少需要2帧");
        }
        for (short[] frame : frames) {
            if (frame == null || frame.length != expectedPixels) {
                throw new IllegalArgumentException("校准样本帧尺寸不一致");
            }
        }
    }

    private double round(double value) {
        return Math.round(value * 10000.0d) / 10000.0d;
    }

    @Getter
    public static final class CalibrationQualityResult {
        private final String analysisVersion;
        private final String calibrationType;
        private final String qualityStatus;
        private final boolean usableForCalibration;
        private final String summaryMessage;
        private final List<String> decisionReasons;
        private final Map<String, Object> metrics;
        private final Map<String, Object> thresholds;

        private CalibrationQualityResult(String analysisVersion,
                                         String calibrationType,
                                         String qualityStatus,
                                         boolean usableForCalibration,
                                         String summaryMessage,
                                         List<String> decisionReasons,
                                         Map<String, Object> metrics,
                                         Map<String, Object> thresholds) {
            this.analysisVersion = analysisVersion;
            this.calibrationType = calibrationType;
            this.qualityStatus = qualityStatus;
            this.usableForCalibration = usableForCalibration;
            this.summaryMessage = summaryMessage;
            this.decisionReasons = Collections.unmodifiableList(new ArrayList<>(decisionReasons));
            this.metrics = Collections.unmodifiableMap(new LinkedHashMap<>(metrics));
            this.thresholds = Collections.unmodifiableMap(new LinkedHashMap<>(thresholds));
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("analysisVersion", analysisVersion);
            map.put("calibrationType", calibrationType);
            map.put("qualityStatus", qualityStatus);
            map.put("usableForCalibration", usableForCalibration);
            map.put("summaryMessage", summaryMessage);
            map.put("decisionReasons", decisionReasons);
            map.put("metrics", metrics);
            map.put("thresholds", thresholds);
            return map;
        }
    }

    private static final class BasicStats {
        private final int[] sortedValues;
        private final int min;
        private final int max;
        private final double mean;
        private final double stddev;
        private final double median;
        private final double mad;
        private final double robustSigma;
        private final double p05;
        private final double p95;
        private final double blackRatio;
        private final double saturationRatio;

        private BasicStats(int[] sortedValues,
                           int min,
                           int max,
                           double mean,
                           double stddev,
                           double median,
                           double mad,
                           double robustSigma,
                           double p05,
                           double p95,
                           double blackRatio,
                           double saturationRatio) {
            this.sortedValues = sortedValues;
            this.min = min;
            this.max = max;
            this.mean = mean;
            this.stddev = stddev;
            this.median = median;
            this.mad = mad;
            this.robustSigma = robustSigma;
            this.p05 = p05;
            this.p95 = p95;
            this.blackRatio = blackRatio;
            this.saturationRatio = saturationRatio;
        }
    }

    private static final class FrameStats {
        private final int frameCount;
        private final double meanOfMeans;
        private final double meanStd;
        private final double meanRange;

        private FrameStats(int frameCount, double meanOfMeans, double meanStd, double meanRange) {
            this.frameCount = frameCount;
            this.meanOfMeans = meanOfMeans;
            this.meanStd = meanStd;
            this.meanRange = meanRange;
        }
    }

    private static final class DefectDecision {
        private final boolean fail;
        private final boolean warning;

        private DefectDecision(boolean fail, boolean warning) {
            this.fail = fail;
            this.warning = warning;
        }
    }
}
