package springbootjni.dto.jni;

import lombok.Data;

@Data
public class BridgeConnectRequest {
    private String host;
    private Integer controlPort;
    private Integer imagePort;
    private Boolean verifyCrc = Boolean.TRUE;
}
