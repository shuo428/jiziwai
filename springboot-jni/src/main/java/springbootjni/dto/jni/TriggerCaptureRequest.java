package springbootjni.dto.jni;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单次触发采集请求。
 *
 * <p>autoProcess 用于控制本次采集保存成功后是否立即执行当前阶段的自动图像处理。
 * 未开启时只保存原图、预览图、完整性结果、基础质量分析和处置建议；
 * 开启时才会根据处置建议自动尝试坏点插值或少量异常行/列校正。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TriggerCaptureRequest {
    private Boolean autoProcess;
}
