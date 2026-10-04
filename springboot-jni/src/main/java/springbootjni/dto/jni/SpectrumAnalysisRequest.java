package springbootjni.dto.jni;

import lombok.Data;

/**
 * 像素域一维光谱的谱峰检测与峰形拟合请求。
 *
 * <p>本模块尚未进行波长标定，因此全部位置参数都是光谱曲线上的 pixelIndex，
 * 不能把结果解释为 nm。</p>
 */
@Data
public class SpectrumAnalysisRequest {
    /**
     * AUTO 优先使用已经保存的预处理曲线；EXTRACTED 强制使用原始一维曲线；
     * PREPROCESSED 强制使用预处理曲线。
     */
    private String source;

    /**
     * 最小突出度占当前曲线动态范围的比例，例如0.04表示峰必须高出局部谷底至少4%的动态范围。
     */
    private Double minProminenceRatio;

    /** 最低峰信噪比，使用鲁棒噪声估计计算。 */
    private Double minSignalToNoise;

    /** 两个被保留峰的最小像素间距，避免同一个宽峰被重复报告。 */
    private Integer minDistancePixels;

    /** 每个候选峰参与高斯拟合的左右半窗口半径，单位为像素点。 */
    private Integer fitWindowRadius;

    /** 最多返回多少个突出度最高的谱峰。 */
    private Integer maxPeaks;
}
