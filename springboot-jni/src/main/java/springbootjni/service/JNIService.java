package springbootjni.service;

import springbootjni.dto.jni.BridgeConnectRequest;
import springbootjni.dto.jni.BridgeStateResponse;

import java.util.List;

public interface JNIService {
    BridgeStateResponse getState();

    BridgeStateResponse connect(BridgeConnectRequest request);

    BridgeStateResponse disconnect();

    void sendReset();

    void sendTriggerOnce();

    void sendQueryStatus();

    void sendFullConfig(List<Integer> configBytes);

    void handleImageFrame(int width, int height, short[] pixels16, byte[] pixels8);

    void handleStatus(int statusBits, int errorCode);

    void handleConfigAck(int resultCode, int failedAddr);

    void handleTransportError(String channel, String message);
}
