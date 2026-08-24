package springbootjni.dto.jni;

import lombok.Data;

@Data
public class BridgeConnectRequest {
    private String host;
    private Integer controlPort;
    private Integer imagePort;
    private Boolean verifyCrc = Boolean.TRUE;
    private Integer expectedWidth = 800;
    private Integer expectedHeight = 600;
    private String pixelFormat = "RAW16_LOW12";
    private String readoutOrder = "GLUX1605_HDR_4LANE_INTERLEAVED_EFFECTIVE";
}
