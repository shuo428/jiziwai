package springbootjni.dto.jni;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单次触发命令的同步返回结果。
 *
 * <p>图片本身仍通过 WebSocket 异步返回；这里返回数据库中的采集记录 ID 和
 * requestId，方便前端日志与后续回调对应。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TriggerCaptureResponse {
    private Long captureId;
    private String requestId;
}
