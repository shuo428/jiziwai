package springbootjni.dto.jni;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 像素域光谱拟合结果。
 *
 * <p>这里的峰中心、FWHM等均以像素位置表示；等待未来有可信标定数据后，
 * 可以在不改变拟合算法的情况下再映射到波长坐标。</p>
 */
@Data
public class SpectrumAnalysisResponse {
    private Long id;
    private Long spectrumId;
    private Long preprocessingId;
    private Long imageId;
    private Long captureId;

    /** EXTRACTED 或 PREPROCESSED。 */
    private String source;
    private String sourceDescription;
    private Integer pointCount;
    private Double dynamicRange;
    private Double noiseEstimate;
    private Integer candidatePeakCount;
    private Integer peakCount;
    private Map<String, Object> analysisParameters;
    private List<Peak> peaks;
    private String algorithmVersion;
    private String summaryMessage;
    private Map<String, Object> details;
    private OffsetDateTime createdAt;

    /** 单个候选谱峰的高斯拟合结果。 */
    @Data
    public static class Peak {
        private Integer rank;
        /** 原始离散曲线中观察到的局部极值像素位置。 */
        private Integer observedPixelIndex;
        /** 高斯曲线连续中心位置，单位为 pixelIndex。 */
        private Double fittedCenterPixel;
        /** 新分析固定为 POSITIVE，保留该字段用于历史结果兼容。 */
        private String polarity;
        private Double observedIntensity;
        private Double fittedPeakIntensity;
        private Double localBaseline;
        /** 正向高斯峰相对局部线性基线的振幅。 */
        private Double amplitude;
        /** 候选峰相对两侧局部背景的突出度，恒为正数。 */
        private Double prominence;
        private Double signalToNoise;
        private Double sigmaPixels;
        private Double fwhmPixels;
        /** 正向高斯模型相对局部基线的积分面积。 */
        private Double area;
        private Double rSquared;
        private Integer fitWindowStart;
        private Integer fitWindowEnd;
        /** GOOD 或 LOW_CONFIDENCE，表示此次峰形拟合的可信程度。 */
        private String fitQuality;
    }
}
