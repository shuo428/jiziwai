package springbootjni.dto.jni;

import lombok.Data;

/**
 * 单张光谱图像的一维光谱提取请求。
 *
 * <p>第一版提取的是像素域光谱：横轴仍是 pixelIndex，而不是 wavelength(nm)。
 * wavelengthAxis 允许 AUTO/X/Y，ROI 使用左闭右开的像素范围，rectifyTilt 控制是否做
 * 基于互相关的整数像素级倾斜矫正。</p>
 */
@Data
public class SpectrumExtractionRequest {
    /** AUTO 优先使用处理后 PASS 图；ORIGINAL 只使用原图；PROCESSED 只使用处理后图。 */
    private String sourceMode;

    /** AUTO 自动判断波长方向；X 表示横向为波长方向；Y 表示纵向为波长方向。 */
    private String wavelengthAxis;

    /** 是否在积分前做行/列整数像素级矫正，默认 true。 */
    private Boolean rectifyTilt;

    /** 互相关搜索的最大偏移像素；为空时由算法根据 ROI 自动给默认值。 */
    private Integer maxShiftPixels;

    /** MEAN 或 SUM；默认 MEAN。 */
    private String integrationMethod;

    /** 可选 ROI；为空时使用整张图。 */
    private Roi roi;

    @Data
    public static class Roi {
        private Integer xStart;
        private Integer xEnd;
        private Integer yStart;
        private Integer yEnd;
    }
}
