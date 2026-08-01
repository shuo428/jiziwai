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
 * 多帧坏点、异常行和异常列检测。
 *
 * <p>算法只使用 primitive 数组和固定大小的临时缓冲区，不为每个像素创建对象。
 * 每个像素在每一帧中先做一次局部离群判断，再使用投票比例确认“持续异常”。
 * 因此它比把一帧中的偶然噪声直接判为坏点更可靠，也保留了与当前单帧算法相同的
 * 8邻域中值/MAD思想。</p>
 *
 * <p>注意：多帧只能证明“同一坐标在多帧中稳定异常”。如果真实光谱线在所有测试帧中
 * 都稳定存在，它仍可能成为候选异常；暗场/平场校准会话用于进一步区分固定传感器缺陷
 * 和真实光谱结构。</p>
 */
@Service
public class SpectralMultiFrameQualityAnalysisService {
    private static final int SENSOR_MAX_DN = 4095;
    private static final int DEFAULT_BAD_PIXEL_MIN_DIFF = 512;
    private static final double DEFAULT_BAD_PIXEL_MAD_MULTIPLIER = 12.0d;

    @Value("${spectral.quality.line-local-radius:3}")
    private int lineLocalRadius;

    @Value("${spectral.quality.line-min-abs-diff-dn:64.0}")
    private double lineMinAbsDiffDn;

    @Value("${spectral.quality.line-relative-diff-ratio:0.05}")
    private double lineRelativeDiffRatio;

    @Value("${spectral.quality.line-mad-multiplier:8.0}")
    private double lineMadMultiplier;

    /** 对一组同尺寸RAW12图像执行多帧投票检测。 */
    public MultiFrameResult analyze(int width,
                                    int height,
                                    List<short[]> frames,
                                    double voteRatio) {
        validate(width, height, frames);
        double normalizedVoteRatio = Math.max(0.5d, Math.min(1.0d, voteRatio));
        int frameCount = frames.size();
        int requiredVotes = Math.max(1, (int) Math.ceil(frameCount * normalizedVoteRatio));

        List<Integer> badPixels = detectBadPixels(width, height, frames, requiredVotes);
        List<Integer> abnormalRows = detectLines(width, height, frames, requiredVotes, true);
        List<Integer> abnormalColumns = detectLines(width, height, frames, requiredVotes, false);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("algorithmVersion", "multi-frame-v1");
        details.put("frameCount", frameCount);
        details.put("voteRatio", normalizedVoteRatio);
        details.put("requiredVotes", requiredVotes);
        details.put("badPixelDetection", "per-frame 8-neighbor median + MAD, then temporal vote");
        details.put("lineDetection", "per-frame line mean + local median/MAD, then temporal vote");
        details.put("note", "固定存在的真实光谱线仍需要暗场/平场校准来排除误判");

        DefectMap defectMap = new DefectMap(
                width,
                height,
                normalizedVoteRatio,
                badPixels,
                abnormalRows,
                abnormalColumns);
        String summary = "多帧检测完成：" + badPixels.size() + " 个疑似坏点，"
                + abnormalRows.size() + " 条异常行，" + abnormalColumns.size() + " 条异常列";
        return new MultiFrameResult(
                width,
                height,
                frameCount,
                normalizedVoteRatio,
                badPixels,
                abnormalRows,
                abnormalColumns,
                defectMap,
                details,
                summary);
    }

    private List<Integer> detectBadPixels(int width,
                                          int height,
                                          List<short[]> frames,
                                          int requiredVotes) {
        int[] voteCounts = new int[width * height];
        int[] neighbors = new int[8];
        int[] deviations = new int[8];

        for (short[] frame : frames) {
            for (int y = 1; y < height - 1; y++) {
                for (int x = 1; x < width - 1; x++) {
                    int index = y * width + x;
                    int value = pixelAt(frame, index);
                    fillEightNeighbors(frame, width, x, y, neighbors);
                    double neighborMedian = medianInPlace(neighbors, neighbors.length);
                    double neighborMad = medianAbsoluteDeviation(neighbors, neighborMedian, deviations);
                    double robustSigma = neighborMad * 1.4826d;
                    double threshold = Math.max(
                            DEFAULT_BAD_PIXEL_MIN_DIFF,
                            DEFAULT_BAD_PIXEL_MAD_MULTIPLIER * Math.max(robustSigma, 1.0d));
                    if (Math.abs(value - neighborMedian) > threshold) {
                        voteCounts[index]++;
                    }
                }
            }
        }

        List<Integer> result = new ArrayList<>();
        for (int index = 0; index < voteCounts.length; index++) {
            if (voteCounts[index] >= requiredVotes) {
                result.add(index);
            }
        }
        return result;
    }

    private List<Integer> detectLines(int width,
                                      int height,
                                      List<short[]> frames,
                                      int requiredVotes,
                                      boolean rows) {
        int lineCount = rows ? height : width;
        int perpendicularLength = rows ? width : height;
        int[] voteCounts = new int[lineCount];

        for (short[] frame : frames) {
            double[] lineMeans = new double[lineCount];
            if (rows) {
                for (int y = 0; y < height; y++) {
                    long sum = 0L;
                    int offset = y * width;
                    for (int x = 0; x < width; x++) {
                        sum += pixelAt(frame, offset + x);
                    }
                    lineMeans[y] = (double) sum / perpendicularLength;
                }
            } else {
                for (int x = 0; x < width; x++) {
                    long sum = 0L;
                    for (int y = 0; y < height; y++) {
                        sum += pixelAt(frame, y * width + x);
                    }
                    lineMeans[x] = (double) sum / perpendicularLength;
                }
            }

            RobustStats global = robustStats(lineMeans);
            double threshold = Math.max(
                    lineMinAbsDiffDn,
                    Math.max(lineMadMultiplier * Math.max(global.robustSigma, 1.0d),
                            Math.abs(global.median) * lineRelativeDiffRatio));
            int radius = Math.max(1, lineLocalRadius);
            for (int index = 0; index < lineCount; index++) {
                double localMedian = localMedian(lineMeans, index, radius);
                if (Math.abs(lineMeans[index] - localMedian) > threshold) {
                    voteCounts[index]++;
                }
            }
        }

        List<Integer> result = new ArrayList<>();
        for (int index = 0; index < voteCounts.length; index++) {
            if (voteCounts[index] >= requiredVotes) {
                result.add(index);
            }
        }
        return result;
    }

    private void validate(int width, int height, List<short[]> frames) {
        if (width <= 2 || height <= 2) {
            throw new IllegalArgumentException("多帧图像宽高必须大于2");
        }
        if (frames == null || frames.size() < 2) {
            throw new IllegalArgumentException("多帧检测至少需要2帧");
        }
        int expectedPixels = Math.multiplyExact(width, height);
        for (short[] frame : frames) {
            if (frame == null || frame.length != expectedPixels) {
                throw new IllegalArgumentException("多帧图像尺寸或像素数组长度不一致");
            }
        }
    }

    private int pixelAt(short[] frame, int index) {
        return frame[index] & SENSOR_MAX_DN;
    }

    private void fillEightNeighbors(short[] frame, int width, int x, int y, int[] neighbors) {
        int offset = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) {
                    continue;
                }
                neighbors[offset++] = pixelAt(frame, (y + dy) * width + x + dx);
            }
        }
    }

    private double localMedian(double[] values, int center, int radius) {
        double[] buffer = new double[Math.max(1, radius * 2)];
        int count = 0;
        for (int index = Math.max(0, center - radius);
             index <= Math.min(values.length - 1, center + radius);
             index++) {
            if (index != center) {
                buffer[count++] = values[index];
            }
        }
        return count == 0 ? values[center] : median(buffer, count);
    }

    private RobustStats robustStats(double[] values) {
        double median = median(values, values.length);
        double[] deviations = new double[values.length];
        for (int index = 0; index < values.length; index++) {
            deviations[index] = Math.abs(values[index] - median);
        }
        double mad = median(deviations, deviations.length);
        return new RobustStats(median, mad, mad * 1.4826d);
    }

    private double medianAbsoluteDeviation(int[] values, double center, int[] deviations) {
        for (int index = 0; index < values.length; index++) {
            deviations[index] = (int) Math.round(Math.abs(values[index] - center));
        }
        return medianInPlace(deviations, values.length);
    }

    /** 对固定大小邻域原地排序，避免在每个像素上分配临时数组。 */
    private double medianInPlace(int[] values, int length) {
        Arrays.sort(values, 0, length);
        int middle = length / 2;
        if ((length & 1) == 1) {
            return values[middle];
        }
        return (values[middle - 1] + values[middle]) / 2.0d;
    }

    private double median(double[] values, int length) {
        double[] sorted = Arrays.copyOf(values, length);
        Arrays.sort(sorted);
        int middle = length / 2;
        if ((length & 1) == 1) {
            return sorted[middle];
        }
        return (sorted[middle - 1] + sorted[middle]) / 2.0d;
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

    @Getter
    public static final class DefectMap {
        private final int width;
        private final int height;
        private final double voteRatio;
        private final List<Integer> badPixelIndexes;
        private final List<Integer> abnormalRows;
        private final List<Integer> abnormalColumns;

        private DefectMap(int width,
                          int height,
                          double voteRatio,
                          List<Integer> badPixelIndexes,
                          List<Integer> abnormalRows,
                          List<Integer> abnormalColumns) {
            this.width = width;
            this.height = height;
            this.voteRatio = voteRatio;
            this.badPixelIndexes = Collections.unmodifiableList(new ArrayList<>(badPixelIndexes));
            this.abnormalRows = Collections.unmodifiableList(new ArrayList<>(abnormalRows));
            this.abnormalColumns = Collections.unmodifiableList(new ArrayList<>(abnormalColumns));
        }

        /** 从已持久化的校准摘要恢复缺陷地图。 */
        public static DefectMap fromPersisted(int width,
                                              int height,
                                              double voteRatio,
                                              List<Integer> badPixelIndexes,
                                              List<Integer> abnormalRows,
                                              List<Integer> abnormalColumns) {
            return new DefectMap(
                    width,
                    height,
                    voteRatio,
                    badPixelIndexes == null ? Collections.<Integer>emptyList() : badPixelIndexes,
                    abnormalRows == null ? Collections.<Integer>emptyList() : abnormalRows,
                    abnormalColumns == null ? Collections.<Integer>emptyList() : abnormalColumns);
        }
    }

    @Getter
    public static final class MultiFrameResult {
        private final int width;
        private final int height;
        private final int frameCount;
        private final double voteRatio;
        private final List<Integer> badPixelIndexes;
        private final List<Integer> abnormalRows;
        private final List<Integer> abnormalColumns;
        private final DefectMap defectMap;
        private final Map<String, Object> details;
        private final String summaryMessage;

        private MultiFrameResult(int width,
                                 int height,
                                 int frameCount,
                                 double voteRatio,
                                 List<Integer> badPixelIndexes,
                                 List<Integer> abnormalRows,
                                 List<Integer> abnormalColumns,
                                 DefectMap defectMap,
                                 Map<String, Object> details,
                                 String summaryMessage) {
            this.width = width;
            this.height = height;
            this.frameCount = frameCount;
            this.voteRatio = voteRatio;
            this.badPixelIndexes = Collections.unmodifiableList(new ArrayList<>(badPixelIndexes));
            this.abnormalRows = Collections.unmodifiableList(new ArrayList<>(abnormalRows));
            this.abnormalColumns = Collections.unmodifiableList(new ArrayList<>(abnormalColumns));
            this.defectMap = defectMap;
            this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
            this.summaryMessage = summaryMessage;
        }
    }
}
