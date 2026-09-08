package springbootjni.dto.jni;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 已保存的光谱几何校正配置。
 */
@Data
public class GeometryProfileResponse {
    private Long id;
    private String profileName;
    private String modeType;
    private Integer imageWidth;
    private Integer imageHeight;
    private String sourceMode;
    private String dispersionAxis;
    private Integer rotateDegrees;
    private Boolean flipX;
    private Boolean flipY;
    private Boolean tiltCorrectionEnabled;
    private Integer maxShiftPixels;
    private SpectrumExtractionResponse.Roi roi;
    private Boolean enabled;
    private String algorithmVersion;
    private Map<String, Object> details;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
