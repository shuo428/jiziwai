package springbootjni.dto.jni;

import lombok.Data;

/** 暗场/平场校准帧的前端预览。 */
@Data
public class CalibrationPreviewResponse {
    private Integer frameIndex;
    private String previewType;
    private String label;
    private String imageDataUrl;
    private String storageUri;
}
