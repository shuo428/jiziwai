package springbootjni.dto.jni;

import lombok.Data;

import java.time.OffsetDateTime;

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
}
