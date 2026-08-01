package springbootjni.dto.jni;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 暗场/平场校准请求。 */
@Data
public class CalibrationRequest {
    private Integer frameCount = 16;
    private Integer width = 800;
    private Integer height = 600;
    private List<Long> imageIds = new ArrayList<>();
}
