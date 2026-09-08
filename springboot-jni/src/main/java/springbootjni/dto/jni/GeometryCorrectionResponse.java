package springbootjni.dto.jni;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 单张二维光谱图像几何校正结果。
 */
@Data
public class GeometryCorrectionResponse {
    private Long id;
    private Long imageId;
    private Long captureId;
    private Long profileId;
    private String modeType;
    private String sourceMode;
    private String sourceQualityStatus;
    private String dispersionAxis;
    private SpectrumExtractionResponse.Roi roi;
    private Boolean orientationApplied;
    private Integer rotateDegrees;
    private Boolean flipX;
    private Boolean flipY;
    private Boolean tiltCorrectionApplied;
    private Integer maxShiftPixels;
    private Integer shiftMin;
    private Integer shiftMax;
    private Double shiftMeanAbs;
    private Double axisConfidence;
    private Integer width;
    private Integer height;
    private String outputRawStorageUri;
    private String outputPreviewStorageUri;
    private String imageDataUrl;
    private String algorithmVersion;
    private String summaryMessage;
    private Map<String, Object> details;
    private OffsetDateTime createdAt;
}
