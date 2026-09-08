package springbootjni.dto.jni;

import lombok.Data;

/**
 * 对一张已保存光谱图像执行二维几何校正的请求。
 */
@Data
public class GeometryCorrectionRequest {
    /** 优先使用某个已保存配置；为空时使用请求体中的临时参数。 */
    private Long profileId;
    /** 未传 profileId 时，是否读取当前模式已启用配置，默认 true。 */
    private Boolean useEnabledProfile;
    /** NORMAL 或 HDR；为空时以后端图像记录为准。 */
    private String modeType;
    private String profileName;
    private String sourceMode;
    private String dispersionAxis;
    private Integer rotateDegrees;
    private Boolean flipX;
    private Boolean flipY;
    private Boolean tiltCorrectionEnabled;
    private Integer maxShiftPixels;
    private SpectrumExtractionRequest.Roi roi;
    /** 是否把本次参数保存成配置；几何结果始终按 image_id 覆盖为最新一条。 */
    private Boolean saveAsEnabledProfile;
}
