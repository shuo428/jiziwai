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
    private String captureScene;
    private OffsetDateTime timestamp;
    private Integer width;
    private Integer height;
    private Long raw8Length;
    private Long raw16Length;
    private Long payloadLength;
    private String pixelFormat;
    /** FPGA 直接输出的有效像素 payload 文件地址，仍保持芯片/FPGA读出顺序。 */
    private String fpgaPayloadStorageUri;
    /** FPGA 原始 payload 的 SHA-256，用于确认转序前数据是否一致。 */
    private String fpgaPayloadSha256;
    /** 当前 payload 使用的空间读出顺序；raw16le.bin 已经按该顺序转成正常行列图。 */
    private String readoutOrder;
    /** HDR高增益平面的RAW16文件地址；captureScene=HDR/HDR_DARK/HDR_FLAT时有值。 */
    private String hgRawStorageUri;
    /** HDR低增益平面的RAW16文件地址；captureScene=HDR/HDR_DARK/HDR_FLAT时有值。 */
    private String lgRawStorageUri;
    /** HDR高增益平面的预览图文件地址。 */
    private String hgPreviewStorageUri;
    /** HDR低增益平面的预览图文件地址。 */
    private String lgPreviewStorageUri;
    /** HDR融合时每个像素采用HG/LG/混合/双饱和的掩码文件地址。 */
    private String hdrFusionMaskStorageUri;
    /** HDR高低增益比例，含义为HG_DN/LG_DN。 */
    private Double hdrGainRatio;
    /** HDR融合规则、阈值、像素来源统计等审计信息。 */
    private Map<String, Object> hdrFusionDetails;
    private String imageDataUrl;
    /** HDR高增益平面预览图data URL。 */
    private String hgImageDataUrl;
    /** HDR低增益平面预览图data URL。 */
    private String lgImageDataUrl;
    /** 暗场扣除、平场校正和稳定缺陷地图修复后的校准版预览图；未启用校准包时为空。 */
    private String calibratedImageDataUrl;
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
