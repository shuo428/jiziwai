package springbootjni.dto.jni;

import lombok.Data;
import springbootjni.jni.SpectraBridgeNative;

@Data
public class BridgeStateResponse {
    private boolean connected;
    private String host;
    private Integer controlPort;
    private Integer imagePort;
    private Boolean verifyCrc;
    private Integer expectedWidth;
    private Integer expectedHeight;
    private String pixelFormat;
    private String readoutOrder;
    private String lastError;
    private String message;
    private Integer fullConfigSize = SpectraBridgeNative.FULL_CONFIG_SIZE;
}
