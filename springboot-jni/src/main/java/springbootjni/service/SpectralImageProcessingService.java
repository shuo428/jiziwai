package springbootjni.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import springbootjni.service.SpectralImageQualityAnalysisService.QualityAnalysisResult;
import springbootjni.service.SpectralImageQualityDispositionService.QualityDispositionResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 当前阶段可执行的 RAW12 图像处理服务。
 *
 * <p>本服务只处理“局部、可定位、可用邻域信息恢复”的轻微异常：
 * 坏点插值和少量异常行/列校正。它不会自动做亮度增强、背景扣除、去噪或饱和峰恢复，
 * 因为那些操作会影响后续光谱定量，应该放在光谱提取前预处理阶段结合 ROI/暗场/平场再做。</p>
 *
 * <p>处理原则：</p>
 * <ol>
 *     <li>永远不修改原始 RAW；</li>
 *     <li>只执行处置策略中 stage=PROCESS 且 repairable=true 的动作；</li>
 *     <li>处理后必须重新进行基础质量分析和处置判断；</li>
 *     <li>处理结果由持久化层另存 processed RAW/preview，并记录到动作日志。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class SpectralImageProcessingService {
    public static final String PROCESSING_VERSION = "spectral-processing-v1";

    private static final int SENSOR_MAX_DN = 4095;
    private static final String ACTION_BAD_PIXEL_INTERPOLATION = "BAD_PIXEL_INTERPOLATION";
    private static final String ACTION_ABNORMAL_LINE_CORRECTION = "ABNORMAL_LINE_CORRECTION";

    private final SpectralImageQualityAnalysisService qualityAnalysisService;
    private final SpectralImageQualityDispositionService qualityDispositionService;

    /**
     * 根据质量处置建议尝试处理图像。
     *
     * @return 没有可执行处理动作时返回 null；否则返回处理后像素和复检结果。
     */
    public ProcessingResult processIfRecommended(int width,
                                                 int height,
                                                 short[] originalPixels16,
                                                 QualityAnalysisResult originalQuality,
                                                 QualityDispositionResult disposition) {
        if (originalQuality == null || disposition == null || originalPixels16 == null) {
            return null;
        }

        Set<String> executableCodes = executableProcessActionCodes(disposition);
        boolean shouldCorrectLines = executableCodes.contains(ACTION_ABNORMAL_LINE_CORRECTION);
        boolean shouldCorrectBadPixels = executableCodes.contains(ACTION_BAD_PIXEL_INTERPOLATION);
        if (!shouldCorrectLines && !shouldCorrectBadPixels) {
            return null;
        }

        short[] processedPixels16 = Arrays.copyOf(originalPixels16, originalPixels16.length);
        List<Map<String, Object>> executedActions = new ArrayList<>();
        List<String> skippedActionCodes = skippedProcessActionCodes(disposition, executableCodes);

        int correctedRowCount = 0;
        int correctedColumnCount = 0;
        int correctedBadPixelCount = 0;

        if (shouldCorrectLines) {
            LineCorrectionResult lineResult = correctAbnormalLines(
                    width,
                    height,
                    processedPixels16,
                    lineIndexes(originalQuality.getDetails(), "abnormalRows"),
                    lineIndexes(originalQuality.getDetails(), "abnormalColumns"));
            correctedRowCount = lineResult.correctedRowCount;
            correctedColumnCount = lineResult.correctedColumnCount;
            if (lineResult.hasCorrection()) {
                executedActions.add(actionSummary(
                        ACTION_ABNORMAL_LINE_CORRECTION,
                        "异常行/列校正",
                        lineResult.correctedPixelCount,
                        "用相邻非异常行/列同位置像素的中值替换少量异常行/列。"));
            }
        }

        if (shouldCorrectBadPixels) {
            BadPixelCorrectionResult badPixelResult = correctBadPixels(width, height, processedPixels16, originalQuality);
            correctedBadPixelCount = badPixelResult.correctedBadPixelCount;
            if (badPixelResult.correctedBadPixelCount > 0) {
                Map<String, Object> action = actionSummary(
                        ACTION_BAD_PIXEL_INTERPOLATION,
                        "坏点插值修复",
                        badPixelResult.correctedBadPixelCount,
                        "用 8 邻域中值替换单帧局部离群坏点。");
                action.put("samples", badPixelResult.samples);
                executedActions.add(action);
            }
        }

        if (executedActions.isEmpty()) {
            return null;
        }

        QualityAnalysisResult processedQuality = qualityAnalysisService.analyze(width, height, processedPixels16);
        QualityDispositionResult processedDisposition = qualityDispositionService.decide(processedQuality);
        String summaryMessage = buildSummaryMessage(
                originalQuality,
                processedQuality,
                processedDisposition,
                correctedRowCount,
                correctedColumnCount,
                correctedBadPixelCount);

        return new ProcessingResult(
                "PROCESSED",
                summaryMessage,
                processedPixels16,
                executedActions,
                skippedActionCodes,
                processedQuality,
                processedDisposition,
                correctedRowCount,
                correctedColumnCount,
                correctedBadPixelCount);
    }

    /**
     * 使用多帧检测得到的缺陷地图处理单张图像。
     *
     * <p>多帧检测负责确认“哪些坐标持续异常”，本方法负责对当前图像执行实际修复。
     * 原始数组不会被修改；修复后仍然重新走单帧质量分析和处置判断。</p>
     */
    public ProcessingResult processWithMultiFrameDefectMap(
            int width,
            int height,
            short[] originalPixels16,
            SpectralMultiFrameQualityAnalysisService.DefectMap defectMap) {
        if (defectMap == null
                || defectMap.getWidth() != width
                || defectMap.getHeight() != height) {
            throw new IllegalArgumentException("多帧缺陷地图与当前图像尺寸不一致");
        }

        short[] processedPixels16 = Arrays.copyOf(originalPixels16, originalPixels16.length);
        List<Map<String, Object>> executedActions = new ArrayList<>();
        int correctedRowCount = 0;
        int correctedColumnCount = 0;
        int correctedBadPixelCount = 0;

        if (!defectMap.getAbnormalRows().isEmpty() || !defectMap.getAbnormalColumns().isEmpty()) {
            LineCorrectionResult lineResult = correctAbnormalLines(
                    width,
                    height,
                    processedPixels16,
                    defectMap.getAbnormalRows(),
                    defectMap.getAbnormalColumns());
            correctedRowCount = lineResult.correctedRowCount;
            correctedColumnCount = lineResult.correctedColumnCount;
            if (lineResult.hasCorrection()) {
                executedActions.add(actionSummary(
                        "MULTI_FRAME_ABNORMAL_LINE_CORRECTION",
                        "多帧异常行/列校正",
                        lineResult.correctedPixelCount,
                        "根据多帧持续异常投票结果，用相邻有效行/列同位置像素校正。"));
            }
        }

        if (!defectMap.getBadPixelIndexes().isEmpty()) {
            correctedBadPixelCount = correctBadPixelIndexes(
                    width,
                    height,
                    processedPixels16,
                    defectMap.getBadPixelIndexes());
            if (correctedBadPixelCount > 0) {
                executedActions.add(actionSummary(
                        "MULTI_FRAME_BAD_PIXEL_INTERPOLATION",
                        "多帧坏点插值修复",
                        correctedBadPixelCount,
                        "根据多帧持续异常投票结果，用当前图像8邻域中值插值。"));
            }
        }

        if (executedActions.isEmpty()) {
            return null;
        }

        QualityAnalysisResult processedQuality = qualityAnalysisService.analyze(
                width,
                height,
                processedPixels16);
        QualityDispositionResult processedDisposition = qualityDispositionService.decide(processedQuality);
        String summary = "多帧缺陷地图修复完成：坏点 " + correctedBadPixelCount
                + " 个，异常行 " + correctedRowCount + " 条，异常列 " + correctedColumnCount + " 条；"
                + processedQuality.getSummaryMessage();

        return new ProcessingResult(
                "PROCESSED",
                summary,
                processedPixels16,
                executedActions,
                Collections.<String>emptyList(),
                processedQuality,
                processedDisposition,
                correctedRowCount,
                correctedColumnCount,
                correctedBadPixelCount);
    }

    private Set<String> executableProcessActionCodes(QualityDispositionResult disposition) {
        Set<String> codes = new HashSet<>();
        for (Map<String, Object> action : disposition.getRecommendedActions()) {
            String code = asString(action.get("code"));
            String stage = asString(action.get("stage"));
            boolean repairable = Boolean.TRUE.equals(action.get("repairable"));
            if (!repairable || !"PROCESS".equals(stage)) {
                continue;
            }
            if (ACTION_BAD_PIXEL_INTERPOLATION.equals(code) || ACTION_ABNORMAL_LINE_CORRECTION.equals(code)) {
                codes.add(code);
            }
        }
        return codes;
    }

    private List<String> skippedProcessActionCodes(QualityDispositionResult disposition, Set<String> executableCodes) {
        List<String> skipped = new ArrayList<>();
        for (Map<String, Object> action : disposition.getRecommendedActions()) {
            String code = asString(action.get("code"));
            String stage = asString(action.get("stage"));
            boolean repairable = Boolean.TRUE.equals(action.get("repairable"));
            if (repairable && "PROCESS".equals(stage) && !executableCodes.contains(code)) {
                skipped.add(code);
            }
        }
        return skipped;
    }

    private LineCorrectionResult correctAbnormalLines(int width,
                                                      int height,
                                                      short[] pixels16,
                                                      List<Integer> abnormalRows,
                                                      List<Integer> abnormalColumns) {
        Set<Integer> rowSet = toBoundedSet(abnormalRows, height);
        Set<Integer> columnSet = toBoundedSet(abnormalColumns, width);

        int correctedRows = 0;
        int correctedColumns = 0;
        int correctedPixels = 0;

        for (Integer row : rowSet) {
            int upper = nearestValidIndex(row, -1, height, rowSet);
            int lower = nearestValidIndex(row, 1, height, rowSet);
            if (upper < 0 && lower < 0) {
                continue;
            }
            short[] replacements = new short[width];
            for (int x = 0; x < width; x++) {
                replacements[x] = medianTwoOrOne(
                        upper >= 0 ? pixelAt(pixels16, width, x, upper) : null,
                        lower >= 0 ? pixelAt(pixels16, width, x, lower) : null);
            }
            System.arraycopy(replacements, 0, pixels16, row * width, width);
            correctedRows++;
            correctedPixels += width;
        }

        for (Integer column : columnSet) {
            int left = nearestValidIndex(column, -1, width, columnSet);
            int right = nearestValidIndex(column, 1, width, columnSet);
            if (left < 0 && right < 0) {
                continue;
            }
            for (int y = 0; y < height; y++) {
                pixels16[y * width + column] = medianTwoOrOne(
                        left >= 0 ? pixelAt(pixels16, width, left, y) : null,
                        right >= 0 ? pixelAt(pixels16, width, right, y) : null);
            }
            correctedColumns++;
            correctedPixels += height;
        }

        return new LineCorrectionResult(correctedRows, correctedColumns, correctedPixels);
    }

    private BadPixelCorrectionResult correctBadPixels(int width,
                                                      int height,
                                                      short[] pixels16,
                                                      QualityAnalysisResult originalQuality) {
        Map<String, Object> thresholds = thresholds(originalQuality);
        int minAbsDiffDn = getInt(thresholds, "badPixelMinAbsDiffDn", 512);
        double madMultiplier = getDouble(thresholds, "badPixelMadMultiplier", 12.0d);

        int[] neighbors = new int[8];
        List<PixelReplacement> replacements = new ArrayList<>();
        List<Map<String, Object>> samples = new ArrayList<>();

        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int value = pixelAt(pixels16, width, x, y);
                fillEightNeighbors(pixels16, width, x, y, neighbors);
                double neighborMedian = median(neighbors);
                double neighborMad = medianAbsoluteDeviation(neighbors, neighborMedian);
                double robustSigma = neighborMad * 1.4826d;
                double threshold = Math.max((double) minAbsDiffDn, madMultiplier * Math.max(robustSigma, 1.0d));
                double deviation = Math.abs((double) value - neighborMedian);

                if (deviation > threshold) {
                    int replacement = clampToRaw12((int) Math.round(neighborMedian));
                    replacements.add(new PixelReplacement(x, y, replacement));
                    if (samples.size() < 32) {
                        Map<String, Object> sample = new LinkedHashMap<>();
                        sample.put("x", x);
                        sample.put("y", y);
                        sample.put("originalValue", value);
                        sample.put("replacementValue", replacement);
                        sample.put("deviation", deviation);
                        sample.put("threshold", threshold);
                        samples.add(sample);
                    }
                }
            }
        }

        for (PixelReplacement replacement : replacements) {
            pixels16[replacement.y * width + replacement.x] = (short) replacement.value;
        }
        return new BadPixelCorrectionResult(replacements.size(), samples);
    }

    private int correctBadPixelIndexes(int width,
                                      int height,
                                      short[] pixels16,
                                      List<Integer> indexes) {
        List<PixelReplacement> replacements = new ArrayList<>();
        int[] neighbors = new int[8];
        for (Integer index : indexes) {
            if (index == null || index < width + 1 || index >= pixels16.length - width - 1) {
                continue;
            }
            int x = index % width;
            int y = index / width;
            if (x <= 0 || x >= width - 1 || y <= 0 || y >= height - 1) {
                continue;
            }
            fillEightNeighbors(pixels16, width, x, y, neighbors);
            int replacement = clampToRaw12((int) Math.round(median(neighbors)));
            replacements.add(new PixelReplacement(x, y, replacement));
        }
        for (PixelReplacement replacement : replacements) {
            pixels16[replacement.y * width + replacement.x] = (short) replacement.value;
        }
        return replacements.size();
    }

    @SuppressWarnings("unchecked")
    private List<Integer> lineIndexes(Map<String, Object> details, String key) {
        if (details == null) {
            return Collections.emptyList();
        }
        Object value = details.get(key);
        if (!(value instanceof List)) {
            return Collections.emptyList();
        }
        List<Integer> indexes = new ArrayList<>();
        for (Object item : (List<?>) value) {
            if (item instanceof Map) {
                Object indexValue = ((Map<String, Object>) item).get("index");
                Integer index = asInteger(indexValue);
                if (index != null) {
                    indexes.add(index);
                }
            }
        }
        return indexes;
    }

    private Set<Integer> toBoundedSet(List<Integer> indexes, int size) {
        Set<Integer> result = new HashSet<>();
        if (indexes == null) {
            return result;
        }
        for (Integer index : indexes) {
            if (index != null && index >= 0 && index < size) {
                result.add(index);
            }
        }
        return result;
    }

    private int nearestValidIndex(int origin, int direction, int size, Set<Integer> abnormalIndexes) {
        int index = origin + direction;
        while (index >= 0 && index < size) {
            if (!abnormalIndexes.contains(index)) {
                return index;
            }
            index += direction;
        }
        return -1;
    }

    private short medianTwoOrOne(Integer first, Integer second) {
        int value;
        if (first != null && second != null) {
            value = (first + second) / 2;
        } else if (first != null) {
            value = first;
        } else if (second != null) {
            value = second;
        } else {
            value = 0;
        }
        return (short) clampToRaw12(value);
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
        int[] deviations = new int[values.length];
        for (int index = 0; index < values.length; index++) {
            deviations[index] = (int) Math.round(Math.abs((double) values[index] - median));
        }
        return median(deviations);
    }

    private int clampToRaw12(int value) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, SENSOR_MAX_DN);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> thresholds(QualityAnalysisResult quality) {
        if (quality == null || quality.getDetails() == null) {
            return Collections.emptyMap();
        }
        Object value = quality.getDetails().get("thresholds");
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return Collections.emptyMap();
    }

    private Map<String, Object> actionSummary(String code, String label, int correctedCount, String method) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("code", code);
        action.put("label", label);
        action.put("correctedCount", correctedCount);
        action.put("method", method);
        return action;
    }

    private String buildSummaryMessage(QualityAnalysisResult originalQuality,
                                       QualityAnalysisResult processedQuality,
                                       QualityDispositionResult processedDisposition,
                                       int correctedRowCount,
                                       int correctedColumnCount,
                                       int correctedBadPixelCount) {
        StringBuilder builder = new StringBuilder("已执行图像处理：");
        List<String> parts = new ArrayList<>();
        if (correctedBadPixelCount > 0) {
            parts.add("坏点插值 " + correctedBadPixelCount + " 个");
        }
        if (correctedRowCount > 0 || correctedColumnCount > 0) {
            parts.add("异常行/列校正 行 " + correctedRowCount + " / 列 " + correctedColumnCount);
        }
        builder.append(parts.isEmpty() ? "无像素被修改" : String.join("；", parts));
        builder.append("。复检质量：")
                .append(originalQuality.getQualityStatus())
                .append(" → ")
                .append(processedQuality.getQualityStatus())
                .append("；处置：")
                .append(processedDisposition.getDispositionStatus());
        return builder.toString();
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Integer asInteger(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private int getInt(Map<String, Object> map, String key, int fallback) {
        Object value = map.get(key);
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

    private double getDouble(Map<String, Object> map, String key, double fallback) {
        Object value = map.get(key);
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

    private static final class PixelReplacement {
        private final int x;
        private final int y;
        private final int value;

        private PixelReplacement(int x, int y, int value) {
            this.x = x;
            this.y = y;
            this.value = value;
        }
    }

    private static final class BadPixelCorrectionResult {
        private final int correctedBadPixelCount;
        private final List<Map<String, Object>> samples;

        private BadPixelCorrectionResult(int correctedBadPixelCount, List<Map<String, Object>> samples) {
            this.correctedBadPixelCount = correctedBadPixelCount;
            this.samples = samples;
        }
    }

    private static final class LineCorrectionResult {
        private final int correctedRowCount;
        private final int correctedColumnCount;
        private final int correctedPixelCount;

        private LineCorrectionResult(int correctedRowCount, int correctedColumnCount, int correctedPixelCount) {
            this.correctedRowCount = correctedRowCount;
            this.correctedColumnCount = correctedColumnCount;
            this.correctedPixelCount = correctedPixelCount;
        }

        private boolean hasCorrection() {
            return correctedPixelCount > 0;
        }
    }

    @Getter
    public static final class ProcessingResult {
        private final String processingStatus;
        private final String summaryMessage;
        private final short[] processedPixels16;
        private final List<Map<String, Object>> executedActions;
        private final List<String> skippedActionCodes;
        private final QualityAnalysisResult processedQuality;
        private final QualityDispositionResult processedDisposition;
        private final int correctedRowCount;
        private final int correctedColumnCount;
        private final int correctedBadPixelCount;

        private ProcessingResult(String processingStatus,
                                 String summaryMessage,
                                 short[] processedPixels16,
                                 List<Map<String, Object>> executedActions,
                                 List<String> skippedActionCodes,
                                 QualityAnalysisResult processedQuality,
                                 QualityDispositionResult processedDisposition,
                                 int correctedRowCount,
                                 int correctedColumnCount,
                                 int correctedBadPixelCount) {
            this.processingStatus = processingStatus;
            this.summaryMessage = summaryMessage;
            this.processedPixels16 = Arrays.copyOf(processedPixels16, processedPixels16.length);
            this.executedActions = Collections.unmodifiableList(new ArrayList<>(executedActions));
            this.skippedActionCodes = Collections.unmodifiableList(new ArrayList<>(skippedActionCodes));
            this.processedQuality = processedQuality;
            this.processedDisposition = processedDisposition;
            this.correctedRowCount = correctedRowCount;
            this.correctedColumnCount = correctedColumnCount;
            this.correctedBadPixelCount = correctedBadPixelCount;
        }
    }
}
