package springbootjni.dto.jni;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 前端使用的图像记录。
 *
 * <p>imageDataUrl 是后端从磁盘预览图读取后生成的 data URL。它只在接口响应和
 * WebSocket 消息中传输，不再保存到浏览器 localStorage。</p>
 */
@Data
public class ImageFrameResponse {
    private Long id;
    private Long captureId;
    private String requestId;
    private OffsetDateTime timestamp;
    private Integer width;
    private Integer height;
    private Long raw8Length;
    private Long raw16Length;
    private Long payloadLength;
    private String pixelFormat;
    private String imageDataUrl;
    private Boolean integrityPassed;
    private String integrityResultCode;
    private String qualityStatus;
    private Integer pixelMin;
    private Integer pixelMax;
    private Double pixelMean;
    private Double pixelStddev;
    private Double blackPixelRatio;
    private Double saturationPixelRatio;
    private Integer abnormalRowCount;
    private Integer abnormalColumnCount;
    private Integer badPixelCount;
    private String qualitySummaryMessage;
    private Map<String, Object> qualityDetails;
    /** 原始 RAW 的硬性检查快照：黑场、饱和和动态范围，不受暗场/平场校准影响。 */
    private Map<String, Object> rawHardQualitySnapshot;
    /** 全局校准实际应用后得到的完整质量分析快照；未应用时为 null。 */
    private Map<String, Object> calibratedQualitySnapshot;
    private Map<String, Object> originalQualitySnapshot;
    private String dispositionStatus;
    private Boolean usableForSpectral;
    private String dispositionMessage;
    private List<Map<String, Object>> recommendedActions;
    private List<String> dispositionReasonCodes;
    private String processedImageDataUrl;
    private String processingStatus;
    private String processingMessage;
    private List<Map<String, Object>> executedProcessingActions;
    private String processedQualityStatus;
    private Integer processedPixelMin;
    private Integer processedPixelMax;
    private Double processedPixelMean;
    private Double processedPixelStddev;
    private Double processedBlackPixelRatio;
    private Double processedSaturationPixelRatio;
    private Integer processedAbnormalRowCount;
    private Integer processedAbnormalColumnCount;
    private Integer processedBadPixelCount;
    private String processedQualitySummaryMessage;
    private Map<String, Object> processedQualityDetails;
    private Map<String, Object> processedQualitySnapshot;
    private String processedDispositionStatus;
    private Boolean processedUsableForSpectral;
    private String processedDispositionMessage;
}
