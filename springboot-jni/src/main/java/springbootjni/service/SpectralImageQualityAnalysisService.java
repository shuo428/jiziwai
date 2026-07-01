package springbootjni.service;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单张光谱图像的基础质量分析服务。
 *
 * <p>这一层只回答一个问题：图像作为一张 CMOS RAW12 图像是否“足够健康”，能否进入后续
 * 图像校正、ROI 定位和光谱提取。它暂时不判断最终光谱曲线的峰位、峰宽、信噪比或波长标定
 * 是否正确；那些属于后续“光谱结果质量评价”。</p>
 *
 * <p>实现思路遵循机器视觉相机/图像传感器评价中成熟、常用的基础检查：</p>
 * <ol>
 *     <li>直方图和基础统计：最小值、最大值、均值、标准差，用于判断灰度范围和整体曝光；</li>
 *     <li>低端/高端裁剪比例：统计接近 0 和接近 12-bit 满量程的像素比例，用于判断欠曝、全黑、
 *     过曝和饱和；</li>
 *     <li>空间非均匀性：检测孤立异常行/列，对应传感器评价中的 row/column defect、
 *     fixed-pattern/non-uniformity 思路；</li>
 *     <li>局部坏点检测：用 8 邻域中值和 MAD（Median Absolute Deviation，中位数绝对偏差）
 *     寻找与邻域显著不一致的孤立像素，用于发现 hot/dead/stuck pixel。</li>
 * </ol>
 *
 * <p>为什么大量使用“中值/MAD”而不是简单平均值/标准差：单张光谱图像里可能存在真实亮谱线、
 * 局部强反射或暗背景。中值和 MAD 对少量极端值更不敏感，工程上常用于鲁棒异常检测。这里的
 * 行列异常也使用局部邻域中值，避免把一条较宽的真实光谱带误判成单行缺陷。</p>
 *
 * <p>重要限制：GLUX1605BSI 手册中的坏点、缺陷行列判定是在暗场、半饱和、饱和、均匀光源并
 * 多帧平均的测试条件下定义的；本服务处理的是普通单帧图像，因此采用“基础健康筛查”而不是
 * 传感器出厂级 defect screen。后续如果增加暗场/平场标定图，应在此基础上再实现严格的
 * DSNU/PRNU、平场坏点和缺陷行列判定。</p>
 */
@Service
public class SpectralImageQualityAnalysisService {
    /** 当前 FPGA/JNI 已经保证低 12 bit 有效；这里仍按 12-bit 动态范围做质量评价。 */
    public static final int SENSOR_BIT_DEPTH = 12;
    public static final int SENSOR_MAX_DN = (1 << SENSOR_BIT_DEPTH) - 1;
    public static final String ANALYSIS_VERSION = "basic-single-frame-v1";

    /**
     * 低端裁剪阈值。小于等于该 DN 的像素被认为接近黑场。
     * 默认 16 DN 约为 12-bit 满量程的 0.39%，用于容忍少量读出噪声和暗电流偏移。
     */
    @Value("${spectral.quality.black-threshold-dn:16}")
    private int blackThresholdDn;

    /**
     * 高端裁剪阈值。大于等于该 DN 的像素被认为接近 ADC 满量程。
     * 默认 4080 DN 距满量程 4095 只有 15 DN，用于发现饱和和高光裁剪。
     */
    @Value("${spectral.quality.saturation-threshold-dn:4080}")
    private int saturationThresholdDn;

    /** 低端裁剪超过该比例时给 WARNING；全黑、遮挡、曝光不足通常会首先触发它。 */
    @Value("${spectral.quality.black-warning-ratio:0.050000}")
    private double blackWarningRatio;

    /** 低端裁剪超过该比例时给 FAIL；默认 50% 表示大面积无有效信号。 */
    @Value("${spectral.quality.black-fail-ratio:0.500000}")
    private double blackFailRatio;

    /** 饱和像素对定量光谱影响很大，因此 WARNING 阈值比黑像素更严格。 */
    @Value("${spectral.quality.saturation-warning-ratio:0.001000}")
    private double saturationWarningRatio;

    /** 饱和面积达到 1% 时，后续峰强度和谱线形状通常已经不可信。 */
    @Value("${spectral.quality.saturation-fail-ratio:0.010000}")
    private double saturationFailRatio;

    /** 动态范围过小时说明图像接近常数图，常见于无光、遮挡或读出异常。 */
    @Value("${spectral.quality.dynamic-range-warning-dn:64}")
    private int dynamicRangeWarningDn;

    /** 动态范围极小时直接 FAIL。 */
    @Value("${spectral.quality.dynamic-range-fail-dn:16}")
    private int dynamicRangeFailDn;

    /** 行/列局部异常检测半径。半径为 3 表示拿前后最多 3 行/列做局部基准。 */
    @Value("${spectral.quality.line-local-radius:3}")
    private int lineLocalRadius;

    /** 行/列均值偏离局部中值至少这么多 DN 才可能判为异常，防止低噪声场景过敏。 */
    @Value("${spectral.quality.line-min-abs-diff-dn:64.0}")
    private double lineMinAbsDiffDn;

    /**
     * 行/列均值偏离局部中值至少达到全局中值的该比例才可能判为异常。
     * GLUX1605BSI 手册中缺陷行/列使用 5% 均值偏差作为条件之一；这里把 5% 作为默认工程参考。
     */
    @Value("${spectral.quality.line-relative-diff-ratio:0.050000}")
    private double lineRelativeDiffRatio;

    /** 行/列异常的鲁棒阈值倍数。MAD 会换算成近似 sigma 后再乘该系数。 */
    @Value("${spectral.quality.line-mad-multiplier:8.0}")
    private double lineMadMultiplier;

    /** 单个坏点与 8 邻域中值至少相差这么多 DN 才可能判为坏点。 */
    @Value("${spectral.quality.bad-pixel-min-abs-diff-dn:512}")
    private int badPixelMinAbsDiffDn;

    /** 坏点检测的鲁棒阈值倍数。阈值 = max(绝对阈值, 邻域 robust sigma * 倍数)。 */
    @Value("${spectral.quality.bad-pixel-mad-multiplier:12.0}")
    private double badPixelMadMultiplier;

    /** 坏点数超过 GLUX1605BSI Grade 1 defect limit 的 60 个时给 WARNING。 */
    @Value("${spectral.quality.bad-pixel-warning-count:60}")
    private int badPixelWarningCount;

    /** 单帧局部检测不是出厂 defect screen，因此 FAIL 阈值比手册 Grade 1 限值更宽松。 */
    @Value("${spectral.quality.bad-pixel-fail-count:500}")
    private int badPixelFailCount;

    /** GLUX1605BSI Grade 1 要求缺陷行/列总数为 0；单帧场景下发现 1 条先给 WARNING。 */
    @Value("${spectral.quality.abnormal-line-warning-count:1}")
    private int abnormalLineWarningCount;

    /** 多条异常行/列说明可能存在传感器、链路或光路问题，默认直接 FAIL。 */
    @Value("${spectral.quality.abnormal-line-fail-count:3}")
    private int abnormalLineFailCount;

    /**
     * 对一张已经通过接收完整性校验的 RAW12 图像做基础质量分析。
     *
     * @param width 图像宽度
     * @param height 图像高度
     * @param pixels16 Java short 容器中的 RAW12 像素；只使用低 12 bit
     * @return 可直接写入 t_image_quality_analysis 的分析结果
     */
    public QualityAnalysisResult analyze(int width, int height, short[] pixels16) {
        validateInput(width, height, pixels16);

        final int totalPixels = width * height;
        final long[] rowSums = new long[height];
        final long[] columnSums = new long[width];
        final int[] histogram = new int[SENSOR_MAX_DN + 1];

        int pixelMin = SENSOR_MAX_DN;
        int pixelMax = 0;
        long sum = 0L;
        double sumSquares = 0.0d;
        int blackPixelCount = 0;
        int saturationPixelCount = 0;

        // 第一遍扫描：基础统计、直方图、行列累加。
        // 这些指标都是 O(width * height)，不会改变图像内容，也不依赖后续光谱提取。
        for (int y = 0; y < height; y++) {
            int rowOffset = y * width;
            for (int x = 0; x < width; x++) {
                int value = pixels16[rowOffset + x] & SENSOR_MAX_DN;

                if (value < pixelMin) {
                    pixelMin = value;
                }
                if (value > pixelMax) {
                    pixelMax = value;
                }

                sum += value;
                sumSquares += (double) value * (double) value;
                histogram[value]++;
                rowSums[y] += value;
                columnSums[x] += value;

                if (value <= blackThresholdDn) {
                    blackPixelCount++;
                }
                if (value >= saturationThresholdDn) {
                    saturationPixelCount++;
                }
            }
        }

        double pixelMean = (double) sum / (double) totalPixels;
        double variance = (sumSquares / (double) totalPixels) - (pixelMean * pixelMean);
        double pixelStddev = Math.sqrt(Math.max(variance, 0.0d));
        double blackPixelRatio = (double) blackPixelCount / (double) totalPixels;
        double saturationPixelRatio = (double) saturationPixelCount / (double) totalPixels;
        int dynamicRange = pixelMax - pixelMin;

        double[] rowMeans = toMeans(rowSums, width);
        double[] columnMeans = toMeans(columnSums, height);
        RobustStats rowStats = robustStats(rowMeans);
        RobustStats columnStats = robustStats(columnMeans);

        List<LineAnomaly> abnormalRows = findLineAnomalies(rowMeans, rowStats);
        List<LineAnomaly> abnormalColumns = findLineAnomalies(columnMeans, columnStats);
        BadPixelResult badPixelResult = detectBadPixels(width, height, pixels16);

        List<String> decisionReasons = new ArrayList<>();
        String qualityStatus = decideQualityStatus(
                blackPixelRatio,
                saturationPixelRatio,
                dynamicRange,
                abnormalRows.size(),
                abnormalColumns.size(),
                badPixelResult.count,
                decisionReasons);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("sensorBitDepth", SENSOR_BIT_DEPTH);
        details.put("sensorMaxDn", SENSOR_MAX_DN);
        details.put("totalPixels", totalPixels);
        details.put("dynamicRangeDn", dynamicRange);
        details.put("blackPixelCount", blackPixelCount);
        details.put("saturationPixelCount", saturationPixelCount);
        details.put("histogramPercentiles", buildPercentileDetails(histogram, totalPixels));
        details.put("rowMeanMedian", rowStats.median);
        details.put("rowMeanMad", rowStats.mad);
        details.put("columnMeanMedian", columnStats.median);
        details.put("columnMeanMad", columnStats.mad);
        details.put("abnormalRows", toLineDetails(abnormalRows));
        details.put("abnormalColumns", toLineDetails(abnormalColumns));
        details.put("badPixelSamples", badPixelResult.samples);
        details.put("decisionReasons", decisionReasons);
        details.put("thresholds", buildThresholdDetails());
        details.put("methodNotes",
                "single-frame basic image QC; strict defect screening still requires dark/flat/saturation averaged calibration images");

        return new QualityAnalysisResult(
                qualityStatus,
                ANALYSIS_VERSION,
                pixelMin,
                pixelMax,
                pixelMean,
                pixelStddev,
                blackPixelRatio,
                saturationPixelRatio,
                abnormalRows.size(),
                abnormalColumns.size(),
                badPixelResult.count,
                details,
                buildSummaryMessage(qualityStatus, decisionReasons));
    }

    private void validateInput(int width, int height, short[] pixels16) {
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
    }

    private double[] toMeans(long[] sums, int divisor) {
        double[] means = new double[sums.length];
        for (int index = 0; index < sums.length; index++) {
            means[index] = (double) sums[index] / (double) divisor;
        }
        return means;
    }

    /**
     * 使用 median + MAD 得到鲁棒中心和离散程度。
     * 对正态分布，MAD * 1.4826 可近似转换为标准差；当存在少量坏点、亮线或暗噪声时，
     * 它比普通标准差更不容易被极端值拉大。
     */
    private RobustStats robustStats(double[] values) {
        double median = median(values);
        double[] deviations = new double[values.length];
        for (int index = 0; index < values.length; index++) {
            deviations[index] = Math.abs(values[index] - median);
        }
        double mad = median(deviations);
        double robustSigma = mad * 1.4826d;
        return new RobustStats(median, mad, robustSigma);
    }

    /**
     * 检测孤立异常行/列。
     *
     * <p>行/列缺陷在传感器评价里通常表现为整行或整列整体偏亮/偏暗。这里不用全局均值直接判定，
     * 而是把当前行/列与附近行/列的中值比较：真实的光谱带通常有一定宽度，不会只影响单独一行；
     * 而读出链路或像素阵列缺陷更容易表现为孤立行/列异常。</p>
     */
    private List<LineAnomaly> findLineAnomalies(double[] lineMeans, RobustStats globalStats) {
        List<LineAnomaly> anomalies = new ArrayList<>();
        int radius = Math.max(lineLocalRadius, 1);
        double robustThreshold = lineMadMultiplier * Math.max(globalStats.robustSigma, 1.0d);
        double relativeThreshold = Math.abs(globalStats.median) * lineRelativeDiffRatio;
        double threshold = Math.max(lineMinAbsDiffDn, Math.max(robustThreshold, relativeThreshold));

        for (int index = 0; index < lineMeans.length; index++) {
            double localMedian = localMedian(lineMeans, index, radius);
            double deviation = Math.abs(lineMeans[index] - localMedian);
            if (deviation > threshold) {
                anomalies.add(new LineAnomaly(index, lineMeans[index], localMedian, deviation, threshold));
            }
        }
        return anomalies;
    }

    private double localMedian(double[] values, int centerIndex, int radius) {
        double[] buffer = new double[radius * 2];
        int count = 0;
        int start = Math.max(0, centerIndex - radius);
        int end = Math.min(values.length - 1, centerIndex + radius);
        for (int index = start; index <= end; index++) {
            if (index == centerIndex) {
                continue;
            }
            buffer[count++] = values[index];
        }
        if (count == 0) {
            return values[centerIndex];
        }
        return median(buffer, count);
    }

    /**
     * 检测孤立坏点。
     *
     * <p>成熟的坏点判定最好基于暗场、半饱和、饱和、均匀光源和多帧平均。当前只有单帧图像，
     * 所以这里采用保守的局部离群检测：如果一个像素与 8 邻域中值相差非常大，并且超过基于邻域
     * MAD 的鲁棒阈值，才计为坏点。这样能发现明显 hot/dead/stuck pixel，同时尽量避免把真实
     * 图像边缘或光谱纹理误判为坏点。</p>
     */
    private BadPixelResult detectBadPixels(int width, int height, short[] pixels16) {
        int count = 0;
        List<Map<String, Object>> samples = new ArrayList<>();
        int[] neighbors = new int[8];

        // 边界像素缺少完整 8 邻域，第一版先跳过。后续如果需要可改成镜像边界或 4 邻域。
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int value = pixelAt(pixels16, width, x, y);
                fillEightNeighbors(pixels16, width, x, y, neighbors);
                double neighborMedian = median(neighbors);
                double neighborMad = medianAbsoluteDeviation(neighbors, neighborMedian);
                double robustSigma = neighborMad * 1.4826d;
                double threshold = Math.max(
                        (double) badPixelMinAbsDiffDn,
                        badPixelMadMultiplier * Math.max(robustSigma, 1.0d));
                double deviation = Math.abs((double) value - neighborMedian);

                if (deviation > threshold) {
                    count++;
                    // 不把所有坐标都写进 JSON，避免一张严重坏图把数据库行撑得很大。
                    if (samples.size() < 32) {
                        Map<String, Object> sample = new LinkedHashMap<>();
                        sample.put("x", x);
                        sample.put("y", y);
                        sample.put("value", value);
                        sample.put("neighborMedian", neighborMedian);
                        sample.put("deviation", deviation);
                        sample.put("threshold", threshold);
                        samples.add(sample);
                    }
                }
            }
        }

        return new BadPixelResult(count, samples);
    }

    private int pixelAt(short[] pixels16, int width, int x, int y) {
        return pixels16[y * width + x] & SENSOR_MAX_DN;
    }

    private void fillEightNeighbors(short[] pixels16, int width, int x, int y, int[] neighbors) {
        int offset = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) {
                    continue;
                }
                neighbors[offset++] = pixelAt(pixels16, width, x + dx, y + dy);
            }
        }
    }

    private String decideQualityStatus(double blackPixelRatio,
                                       double saturationPixelRatio,
                                       int dynamicRange,
                                       int abnormalRowCount,
                                       int abnormalColumnCount,
                                       int badPixelCount,
                                       List<String> reasons) {
        boolean fail = false;
        boolean warning = false;

        if (blackPixelRatio >= blackFailRatio) {
            fail = true;
            reasons.add("黑场/低端裁剪比例达到FAIL阈值");
        } else if (blackPixelRatio >= blackWarningRatio) {
            warning = true;
            reasons.add("黑场/低端裁剪比例达到WARNING阈值");
        }

        if (saturationPixelRatio >= saturationFailRatio) {
            fail = true;
            reasons.add("饱和像素比例达到FAIL阈值");
        } else if (saturationPixelRatio >= saturationWarningRatio) {
            warning = true;
            reasons.add("饱和像素比例达到WARNING阈值");
        }

        if (dynamicRange <= dynamicRangeFailDn) {
            fail = true;
            reasons.add("动态范围过小，图像接近常数图");
        } else if (dynamicRange <= dynamicRangeWarningDn) {
            warning = true;
            reasons.add("动态范围偏小");
        }

        int abnormalLineCount = abnormalRowCount + abnormalColumnCount;
        if (abnormalLineCount >= abnormalLineFailCount) {
            fail = true;
            reasons.add("异常行列数量达到FAIL阈值");
        } else if (abnormalLineCount >= abnormalLineWarningCount) {
            warning = true;
            reasons.add("发现异常行/列");
        }

        if (badPixelCount >= badPixelFailCount) {
            fail = true;
            reasons.add("局部坏点数量达到FAIL阈值");
        } else if (badPixelCount > badPixelWarningCount) {
            warning = true;
            reasons.add("局部坏点数量超过WARNING阈值");
        }

        if (fail) {
            return "FAIL";
        }
        if (warning) {
            return "WARNING";
        }
        reasons.add("基础质量指标均在默认阈值内");
        return "PASS";
    }

    private String buildSummaryMessage(String qualityStatus, List<String> decisionReasons) {
        StringBuilder builder = new StringBuilder("基础质量分析");
        builder.append(qualityStatus);
        if (!decisionReasons.isEmpty()) {
            builder.append(": ");
            for (int index = 0; index < decisionReasons.size(); index++) {
                if (index > 0) {
                    builder.append("；");
                }
                builder.append(decisionReasons.get(index));
            }
        }
        return builder.toString();
    }

    private Map<String, Object> buildThresholdDetails() {
        Map<String, Object> thresholds = new LinkedHashMap<>();
        thresholds.put("blackThresholdDn", blackThresholdDn);
        thresholds.put("saturationThresholdDn", saturationThresholdDn);
        thresholds.put("blackWarningRatio", blackWarningRatio);
        thresholds.put("blackFailRatio", blackFailRatio);
        thresholds.put("saturationWarningRatio", saturationWarningRatio);
        thresholds.put("saturationFailRatio", saturationFailRatio);
        thresholds.put("dynamicRangeWarningDn", dynamicRangeWarningDn);
        thresholds.put("dynamicRangeFailDn", dynamicRangeFailDn);
        thresholds.put("lineLocalRadius", lineLocalRadius);
        thresholds.put("lineMinAbsDiffDn", lineMinAbsDiffDn);
        thresholds.put("lineRelativeDiffRatio", lineRelativeDiffRatio);
        thresholds.put("lineMadMultiplier", lineMadMultiplier);
        thresholds.put("badPixelMinAbsDiffDn", badPixelMinAbsDiffDn);
        thresholds.put("badPixelMadMultiplier", badPixelMadMultiplier);
        thresholds.put("badPixelWarningCount", badPixelWarningCount);
        thresholds.put("badPixelFailCount", badPixelFailCount);
        thresholds.put("abnormalLineWarningCount", abnormalLineWarningCount);
        thresholds.put("abnormalLineFailCount", abnormalLineFailCount);
        return thresholds;
    }

    private Map<String, Object> buildPercentileDetails(int[] histogram, int totalPixels) {
        Map<String, Object> percentiles = new LinkedHashMap<>();
        percentiles.put("p01", percentileFromHistogram(histogram, totalPixels, 0.01d));
        percentiles.put("p05", percentileFromHistogram(histogram, totalPixels, 0.05d));
        percentiles.put("p50", percentileFromHistogram(histogram, totalPixels, 0.50d));
        percentiles.put("p95", percentileFromHistogram(histogram, totalPixels, 0.95d));
        percentiles.put("p99", percentileFromHistogram(histogram, totalPixels, 0.99d));
        return percentiles;
    }

    private int percentileFromHistogram(int[] histogram, int totalPixels, double percentile) {
        int targetRank = (int) Math.ceil(percentile * totalPixels);
        if (targetRank <= 0) {
            targetRank = 1;
        }

        int cumulative = 0;
        for (int value = 0; value < histogram.length; value++) {
            cumulative += histogram[value];
            if (cumulative >= targetRank) {
                return value;
            }
        }
        return histogram.length - 1;
    }

    private List<Map<String, Object>> toLineDetails(List<LineAnomaly> anomalies) {
        List<Map<String, Object>> result = new ArrayList<>();
        int limit = Math.min(anomalies.size(), 64);
        for (int index = 0; index < limit; index++) {
            LineAnomaly anomaly = anomalies.get(index);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", anomaly.index);
            item.put("mean", anomaly.mean);
            item.put("localMedian", anomaly.localMedian);
            item.put("deviation", anomaly.deviation);
            item.put("threshold", anomaly.threshold);
            result.add(item);
        }
        return result;
    }

    private double median(double[] values) {
        return median(values, values.length);
    }

    private double median(double[] values, int count) {
        double[] copy = Arrays.copyOf(values, count);
        Arrays.sort(copy);
        int middle = count / 2;
        if (count % 2 == 1) {
            return copy[middle];
        }
        return (copy[middle - 1] + copy[middle]) / 2.0d;
    }

    private double median(int[] values) {
        int[] copy = Arrays.copyOf(values, values.length);
        Arrays.sort(copy);
        int middle = copy.length / 2;
        if (copy.length % 2 == 1) {
            return copy[middle];
        }
        return ((double) copy[middle - 1] + (double) copy[middle]) / 2.0d;
    }

    private double medianAbsoluteDeviation(int[] values, double median) {
        double[] deviations = new double[values.length];
        for (int index = 0; index < values.length; index++) {
            deviations[index] = Math.abs((double) values[index] - median);
        }
        return median(deviations);
    }

    private static final class RobustStats {
        private final double median;
        private final double mad;
        private final double robustSigma;

        private RobustStats(double median, double mad, double robustSigma) {
            this.median = median;
            this.mad = mad;
            this.robustSigma = robustSigma;
        }
    }

    private static final class LineAnomaly {
        private final int index;
        private final double mean;
        private final double localMedian;
        private final double deviation;
        private final double threshold;

        private LineAnomaly(int index, double mean, double localMedian, double deviation, double threshold) {
            this.index = index;
            this.mean = mean;
            this.localMedian = localMedian;
            this.deviation = deviation;
            this.threshold = threshold;
        }
    }

    private static final class BadPixelResult {
        private final int count;
        private final List<Map<String, Object>> samples;

        private BadPixelResult(int count, List<Map<String, Object>> samples) {
            this.count = count;
            this.samples = samples;
        }
    }

    @Getter
    public static final class QualityAnalysisResult {
        private final String qualityStatus;
        private final String analysisVersion;
        private final int pixelMin;
        private final int pixelMax;
        private final double pixelMean;
        private final double pixelStddev;
        private final double blackPixelRatio;
        private final double saturationPixelRatio;
        private final int abnormalRowCount;
        private final int abnormalColumnCount;
        private final int badPixelCount;
        private final Map<String, Object> details;
        private final String summaryMessage;

        public QualityAnalysisResult(String qualityStatus,
                                     String analysisVersion,
                                     int pixelMin,
                                     int pixelMax,
                                     double pixelMean,
                                     double pixelStddev,
                                     double blackPixelRatio,
                                     double saturationPixelRatio,
                                     int abnormalRowCount,
                                     int abnormalColumnCount,
                                     int badPixelCount,
                                     Map<String, Object> details,
                                     String summaryMessage) {
            this.qualityStatus = qualityStatus;
            this.analysisVersion = analysisVersion;
            this.pixelMin = pixelMin;
            this.pixelMax = pixelMax;
            this.pixelMean = pixelMean;
            this.pixelStddev = pixelStddev;
            this.blackPixelRatio = blackPixelRatio;
            this.saturationPixelRatio = saturationPixelRatio;
            this.abnormalRowCount = abnormalRowCount;
            this.abnormalColumnCount = abnormalColumnCount;
            this.badPixelCount = badPixelCount;
            this.details = details;
            this.summaryMessage = summaryMessage;
        }

        /**
         * t_spectral_capture 使用 FAILED，而质量表使用 FAIL；这里集中做一次枚举映射。
         */
        public String toCaptureStatus() {
            if ("FAIL".equals(qualityStatus)) {
                return "FAILED";
            }
            return qualityStatus;
        }
    }
}
