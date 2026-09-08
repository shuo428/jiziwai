package springbootjni.dto.jni;

import lombok.Data;

import java.util.Map;

/**
 * 光谱二维图像几何校正配置。
 *
 * <p>这里保存的是“怎么把二维光谱图摆正”的参数，而不是波长标定参数。
 * 第一版/第二版只做 ROI、波长方向、旋转/翻转和整数像素级倾斜校正；
 * 更高阶的 smile/keystone 多项式校正需要等真实谱线图像到来后再补。</p>
 */
@Data
public class GeometryProfileRequest {
    private Long id;
    private String profileName;
    /** NORMAL 或 HDR。 */
    private String modeType;
    /** AUTO / ORIGINAL / CALIBRATED / PROCESSED。 */
    private String sourceMode;
    /** AUTO / X / Y。X 表示横向为波长方向，Y 表示纵向为波长方向。 */
    private String dispersionAxis;
    /** 只允许 0/90/180/270，顺时针旋转。 */
    private Integer rotateDegrees;
    private Boolean flipX;
    private Boolean flipY;
    private Boolean tiltCorrectionEnabled;
    private Integer maxShiftPixels;
    private SpectrumExtractionRequest.Roi roi;
    /** 保存时是否同时设为当前模式默认配置。 */
    private Boolean enabled;
    private Map<String, Object> details;
}
