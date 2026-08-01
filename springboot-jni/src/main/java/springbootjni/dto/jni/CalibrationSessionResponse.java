package springbootjni.dto.jni;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

/** 暗场/平场校准会话摘要。 */
@Data
public class CalibrationSessionResponse {
    private Long id;
    /** 按用户和校准类型独立递增的会话编号；不使用跨类型的数据库主键作展示编号。 */
    private Integer sessionNumber;
    private String calibrationType;
    private String acquisitionMode;
    private String status;
    private Integer expectedFrameCount;
    private Integer frameCount;
    private Integer width;
    private Integer height;
    private Integer badPixelCount;
    private Integer badRowCount;
    private Integer badColumnCount;
    private String storageUri;
    private String defectMapUri;
    private String message;
    private Map<String, Object> summary;
    private OffsetDateTime createdAt;
    private OffsetDateTime completedAt;
}
