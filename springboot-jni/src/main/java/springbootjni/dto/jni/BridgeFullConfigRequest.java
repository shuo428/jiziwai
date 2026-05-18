package springbootjni.dto.jni;

import java.util.List;

import lombok.Data;

@Data
public class BridgeFullConfigRequest {
    private List<Integer> configBytes;
}
