package springbootjni.dto.jni;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 多帧质量检测结果。 */
@Data
public class MultiFrameAnalysisResponse {
    private String mode = "MULTI_FRAME";
    private Integer frameCount;
    private Integer width;
    private Integer height;
    private Double voteRatio;
    private Integer badPixelCount;
    private Integer abnormalRowCount;
    private Integer abnormalColumnCount;
    private List<Integer> badPixelIndexes = new ArrayList<>();
    private List<Integer> abnormalRows = new ArrayList<>();
    private List<Integer> abnormalColumns = new ArrayList<>();
    private List<Long> analyzedImageIds = new ArrayList<>();
    private List<Long> repairedImageIds = new ArrayList<>();
    private String summaryMessage;
    private Map<String, Object> details;
    private List<ImageFrameResponse> frames = new ArrayList<>();
}
