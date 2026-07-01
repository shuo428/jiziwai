package springbootjni.service;

import lombok.Getter;
import org.springframework.stereotype.Service;
import springbootjni.service.SpectralImageQualityAnalysisService.QualityAnalysisResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单张图像质量处置策略服务。
 *
 * <p>质量分析服务只回答“这张图像的基础指标是什么、质量状态是什么”；
 * 本服务进一步回答“这张图下一步应该怎么处理”。这里刻意不直接修改图像内容，
 * 而是输出可落库、可展示、可被后续图像处理模块消费的处置建议。</p>
 *
 * <p>当前策略遵循三个原则：</p>
 * <ol>
 *     <li>PASS：原始图像可以直接进入光谱提取，但后续仍可执行暗场、平场等标准预处理；</li>
 *     <li>WARNING：不直接丢弃，优先给出处理/复检建议，处理后需要重新质量分析；</li>
 *     <li>FAIL：默认不进入光谱提取，保留原图用于追溯，并建议重新采集。</li>
 * </ol>
 */
@Service
public class SpectralImageQualityDispositionService {
    public static final String STRATEGY_VERSION = "quality-disposition-v1";

    private static final String STATUS_PASS = "PASS";
    private static final String STATUS_WARNING = "WARNING";
    private static final String STATUS_FAIL = "FAIL";

    /**
     * 根据基础质量分析结果生成处置建议。
     *
     * @param quality 单张图像基础质量分析结果
     * @return 面向流程控制和前端展示的处置结果
     */
    public QualityDispositionResult decide(QualityAnalysisResult quality) {
        if (quality == null || quality.getQualityStatus() == null) {
            List<String> reasonCodes = new ArrayList<>();
            reasonCodes.add("QUALITY_NOT_EVALUATED");
            List<Map<String, Object>> actions = new ArrayList<>();
            actions.add(action(
                    "MANUAL_REVIEW",
                    "人工复核",
                    "REVIEW",
                    "WARNING",
                    "没有可用的基础质量分析结果，不能自动判断是否可用于光谱提取。",
                    false));
            return new QualityDispositionResult(
                    "MANUAL_REVIEW",
                    false,
                    "质量尚未评估，需要人工复核后再决定是否进入后续流程。",
                    actions,
                    reasonCodes,
                    buildDetails(quality, actions, reasonCodes));
        }

        List<String> reasonCodes = new ArrayList<>();
        List<Map<String, Object>> actions = new ArrayList<>();
        appendMetricDrivenActions(quality, actions, reasonCodes);

        String qualityStatus = quality.getQualityStatus();
        String dispositionStatus;
        boolean usableForSpectral;
        String summaryMessage;

        if (STATUS_PASS.equals(qualityStatus)) {
            dispositionStatus = "USE_AS_IS";
            usableForSpectral = true;
            if (actions.isEmpty()) {
                reasonCodes.add("QUALITY_PASS");
                actions.add(action(
                        "DIRECT_USE",
                        "直接进入光谱提取",
                        "USE",
                        "INFO",
                        "基础质量指标均在默认阈值内，当前原始图像不需要修复性处理。",
                        true));
            }
            summaryMessage = "处置建议：质量通过，可直接进入光谱提取；后续仍可按流程执行暗场/平场/背景扣除等标准预处理。";
        } else if (STATUS_WARNING.equals(qualityStatus)) {
            dispositionStatus = "PROCESS_REQUIRED";
            usableForSpectral = false;
            ensureFallbackAction(actions,
                    "QUALITY_WARNING_REVIEW",
                    "处理后复检",
                    "PROCESS",
                    "WARNING",
                    "图像存在轻微质量风险，建议先按异常指标处理或人工确认，再重新计算质量指标。",
                    true);
            summaryMessage = "处置建议：质量为WARNING，建议处理或人工确认后重新质量分析，复检通过后再进入光谱提取。";
        } else if (STATUS_FAIL.equals(qualityStatus)) {
            dispositionStatus = "RECAPTURE_RECOMMENDED";
            usableForSpectral = false;
            ensureFallbackAction(actions,
                    "RECAPTURE",
                    "重新采集",
                    "RECAPTURE",
                    "FAIL",
                    "图像质量达到FAIL，当前帧不建议用于光谱提取，应保留原图并重新采集。",
                    false);
            summaryMessage = "处置建议：质量为FAIL，当前帧不进入光谱提取，保留原图用于追溯并建议重新采集。";
        } else {
            dispositionStatus = "MANUAL_REVIEW";
            usableForSpectral = false;
            ensureFallbackAction(actions,
                    "MANUAL_REVIEW",
                    "人工复核",
                    "REVIEW",
                    "WARNING",
                    "质量状态不是已知枚举，不能自动进入后续流程。",
                    false);
            reasonCodes.add("UNKNOWN_QUALITY_STATUS");
            summaryMessage = "处置建议：质量状态未知，需要人工复核。";
        }

        return new QualityDispositionResult(
                dispositionStatus,
                usableForSpectral,
                summaryMessage,
                actions,
                reasonCodes,
                buildDetails(quality, actions, reasonCodes));
    }

    /**
     * 将每个异常指标映射为可以执行或可以提示的动作。
     *
     * <p>坏点和少量异常行/列通常属于可修复问题；黑像素、饱和和动态范围异常更接近
     * 采集条件或光路问题，严重时不能靠后处理恢复真实光谱信息，因此会提示重新采集。</p>
     */
    private void appendMetricDrivenActions(QualityAnalysisResult quality,
                                           List<Map<String, Object>> actions,
                                           List<String> reasonCodes) {
        Map<String, Object> thresholds = getThresholds(quality);
        double blackWarningRatio = getDouble(thresholds, "blackWarningRatio", 0.05d);
        double blackFailRatio = getDouble(thresholds, "blackFailRatio", 0.50d);
        double saturationWarningRatio = getDouble(thresholds, "saturationWarningRatio", 0.001d);
        double saturationFailRatio = getDouble(thresholds, "saturationFailRatio", 0.01d);
        int dynamicRangeWarningDn = getInt(thresholds, "dynamicRangeWarningDn", 64);
        int dynamicRangeFailDn = getInt(thresholds, "dynamicRangeFailDn", 16);
        int abnormalLineWarningCount = getInt(thresholds, "abnormalLineWarningCount", 1);
        int abnormalLineFailCount = getInt(thresholds, "abnormalLineFailCount", 3);
        int badPixelWarningCount = getInt(thresholds, "badPixelWarningCount", 60);
        int badPixelFailCount = getInt(thresholds, "badPixelFailCount", 500);

        int dynamicRange = getDynamicRange(quality);
        int abnormalLineCount = quality.getAbnormalRowCount() + quality.getAbnormalColumnCount();

        if (quality.getBlackPixelRatio() >= blackFailRatio) {
            reasonCodes.add("BLACK_RATIO_FAIL");
            actions.add(action(
                    "RECAPTURE_WITH_MORE_SIGNAL",
                    "提高有效信号后重采",
                    "RECAPTURE",
                    "FAIL",
                    "黑像素比例达到FAIL阈值，常见于无光、遮挡或严重欠曝，后处理不能恢复不存在的真实信号。",
                    false));
        } else if (quality.getBlackPixelRatio() >= blackWarningRatio) {
            reasonCodes.add("BLACK_RATIO_WARNING");
            actions.add(action(
                    "CHECK_EXPOSURE_OR_LIGHT_PATH",
                    "检查曝光/光路",
                    "REVIEW",
                    "WARNING",
                    "黑像素比例偏高，需确认是否欠曝、遮挡或有效光谱区域过小；仅做亮度拉伸不能替代真实信号采集。",
                    false));
        }

        if (quality.getSaturationPixelRatio() >= saturationFailRatio) {
            reasonCodes.add("SATURATION_RATIO_FAIL");
            actions.add(action(
                    "RECAPTURE_WITH_LOWER_EXPOSURE",
                    "降低曝光后重采",
                    "RECAPTURE",
                    "FAIL",
                    "饱和比例达到FAIL阈值，峰值已经被裁剪，定量强度无法从后处理中可靠恢复。",
                    false));
        } else if (quality.getSaturationPixelRatio() >= saturationWarningRatio) {
            reasonCodes.add("SATURATION_RATIO_WARNING");
            actions.add(action(
                    "SATURATION_MASKING_REVIEW",
                    "标记饱和像素并复核",
                    "REVIEW",
                    "WARNING",
                    "少量饱和像素可先标记或屏蔽，但如果位于主要谱线峰值，应优先降低曝光重新采集。",
                    false));
        }

        if (dynamicRange <= dynamicRangeFailDn) {
            reasonCodes.add("DYNAMIC_RANGE_FAIL");
            actions.add(action(
                    "RECAPTURE_AFTER_LIGHT_PATH_CHECK",
                    "检查光路后重采",
                    "RECAPTURE",
                    "FAIL",
                    "动态范围过小且接近常数图，说明有效灰度变化不足，后处理无法生成真实光谱细节。",
                    false));
        } else if (dynamicRange <= dynamicRangeWarningDn) {
            reasonCodes.add("DYNAMIC_RANGE_WARNING");
            actions.add(action(
                    "CONTRAST_NORMALIZATION_REVIEW",
                    "对比度归一化并复检",
                    "PROCESS",
                    "WARNING",
                    "动态范围偏小，可尝试归一化改善后续算法稳定性，但仍需复检确认真实信号充足。",
                    true));
        }

        if (abnormalLineCount >= abnormalLineFailCount) {
            reasonCodes.add("ABNORMAL_LINE_FAIL");
            actions.add(action(
                    "RECAPTURE_AND_CHECK_READOUT_CHAIN",
                    "重采并检查读出链路",
                    "RECAPTURE",
                    "FAIL",
                    "异常行/列数量达到FAIL，多条条纹可能来自传感器列链路或FPGA读出异常，直接修复风险较高。",
                    false));
        } else if (abnormalLineCount >= abnormalLineWarningCount) {
            reasonCodes.add("ABNORMAL_LINE_WARNING");
            actions.add(action(
                    "ABNORMAL_LINE_CORRECTION",
                    "异常行/列校正",
                    "PROCESS",
                    "WARNING",
                    "少量异常行/列可通过邻域插值或条纹校正处理，处理后需要重新评估行列一致性。",
                    true));
        }

        if (quality.getBadPixelCount() >= badPixelFailCount) {
            reasonCodes.add("BAD_PIXEL_FAIL");
            actions.add(action(
                    "RECAPTURE_AND_SENSOR_DEFECT_REVIEW",
                    "重采并复核坏点",
                    "RECAPTURE",
                    "FAIL",
                    "坏点数量达到FAIL，可能影响谱线积分和假峰判断，建议重采并复核传感器/暗场缺陷。",
                    false));
        } else if (quality.getBadPixelCount() > badPixelWarningCount) {
            reasonCodes.add("BAD_PIXEL_WARNING");
            actions.add(action(
                    "BAD_PIXEL_INTERPOLATION",
                    "坏点插值修复",
                    "PROCESS",
                    "WARNING",
                    "坏点数量超过WARNING阈值，可用邻域中值或局部插值修复，处理后重新评估坏点数量。",
                    true));
        }
    }

    private void ensureFallbackAction(List<Map<String, Object>> actions,
                                      String code,
                                      String label,
                                      String stage,
                                      String severity,
                                      String reason,
                                      boolean repairable) {
        if (!actions.isEmpty()) {
            return;
        }
        actions.add(action(code, label, stage, severity, reason, repairable));
    }

    private Map<String, Object> action(String code,
                                       String label,
                                       String stage,
                                       String severity,
                                       String reason,
                                       boolean repairable) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("code", code);
        action.put("label", label);
        action.put("stage", stage);
        action.put("severity", severity);
        action.put("reason", reason);
        action.put("repairable", repairable);
        return action;
    }

    private Map<String, Object> buildDetails(QualityAnalysisResult quality,
                                             List<Map<String, Object>> actions,
                                             List<String> reasonCodes) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("strategyVersion", STRATEGY_VERSION);
        details.put("qualityStatus", quality == null ? null : quality.getQualityStatus());
        details.put("reasonCodes", reasonCodes);
        details.put("actions", actions);
        details.put("notes", "disposition only decides next step; image repair is executed by later processing module");
        return details;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getThresholds(QualityAnalysisResult quality) {
        if (quality == null || quality.getDetails() == null) {
            return Collections.emptyMap();
        }
        Object thresholds = quality.getDetails().get("thresholds");
        if (thresholds instanceof Map) {
            return (Map<String, Object>) thresholds;
        }
        return Collections.emptyMap();
    }

    private int getDynamicRange(QualityAnalysisResult quality) {
        if (quality == null) {
            return 0;
        }
        Object value = quality.getDetails() == null ? null : quality.getDetails().get("dynamicRangeDn");
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                // fall through to pixel max-min
            }
        }
        return quality.getPixelMax() - quality.getPixelMin();
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

    @Getter
    public static final class QualityDispositionResult {
        private final String dispositionStatus;
        private final boolean usableForSpectral;
        private final String summaryMessage;
        private final List<Map<String, Object>> recommendedActions;
        private final List<String> reasonCodes;
        private final Map<String, Object> details;

        public QualityDispositionResult(String dispositionStatus,
                                        boolean usableForSpectral,
                                        String summaryMessage,
                                        List<Map<String, Object>> recommendedActions,
                                        List<String> reasonCodes,
                                        Map<String, Object> details) {
            this.dispositionStatus = dispositionStatus;
            this.usableForSpectral = usableForSpectral;
            this.summaryMessage = summaryMessage;
            this.recommendedActions = recommendedActions == null
                    ? Collections.<Map<String, Object>>emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(recommendedActions));
            this.reasonCodes = reasonCodes == null
                    ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(reasonCodes));
            this.details = details == null
                    ? Collections.<String, Object>emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(details));
        }
    }
}
