package springbootjni.dto.jni;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 多帧质量检测/修复请求。imageIds按采集顺序传入。 */
@Data
public class MultiFrameAnalysisRequest {
    private List<Long> imageIds = new ArrayList<>();
    private Boolean repair = Boolean.FALSE;
    private Double voteRatio = 0.6d;
}
