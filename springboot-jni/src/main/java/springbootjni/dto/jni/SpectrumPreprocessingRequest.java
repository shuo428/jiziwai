package springbootjni.dto.jni;

import lombok.Data;

/**
 * 一维光谱预处理请求。
 *
 * <p>这里的预处理只作用在已经提取出来的 pixelIndex-intensity 曲线上，
 * 不直接修改二维 RAW 图像。这样可以避免二维图像被过度增强、平滑后影响后续定量分析。</p>
 */
@Data
public class SpectrumPreprocessingRequest {
    /**
     * 是否启用孤立尖刺修正。主要面向宇宙射线、瞬时噪声、单点异常等窄宽度异常。
     */
    private Boolean spikeCorrectionEnabled;

    /**
     * 尖刺检测时左右邻域半径。例如2表示使用当前点左右各2个点估计局部背景。
     */
    private Integer spikeWindowRadius;

    /**
     * 尖刺判定阈值，单位是鲁棒噪声sigma的倍数。数值越大越保守。
     */
    private Double spikeThresholdMad;

    /**
     * 是否启用平滑。平滑用于降低高频读出噪声，但会轻微降低尖锐谱线的峰值。
     */
    private Boolean smoothingEnabled;

    /**
     * 平滑方法：MOVING_AVERAGE 或 SAVITZKY_GOLAY。
     */
    private String smoothingMethod;

    /**
     * 平滑窗口宽度，必须为奇数；后端会自动归一化到合理范围。
     */
    private Integer smoothingWindow;

    /**
     * Savitzky-Golay 多项式阶数，默认2阶。
     */
    private Integer smoothingPolynomialOrder;

    /**
     * 是否启用背景/基线扣除。背景扣除会改变绝对强度，默认应谨慎开启。
     */
    private Boolean baselineCorrectionEnabled;

    /**
     * 背景估计方法。当前实现 ROLLING_PERCENTILE，即滑动窗口低分位数背景。
     */
    private String baselineMethod;

    /**
     * 背景估计窗口宽度，窗口越大越适合缓慢变化背景，越小越容易贴着真实谱峰。
     */
    private Integer baselineWindow;

    /**
     * 背景估计使用的局部低分位数，默认10，表示使用每个窗口内10%分位作为背景候选。
     */
    private Double baselinePercentile;

    /**
     * 是否启用归一化。归一化之后强度不再是原始DN量纲，而是相对强度。
     */
    private Boolean normalizationEnabled;

    /**
     * 归一化方法：NONE、MAX、AREA、MIN_MAX。
     */
    private String normalizationMethod;
}
