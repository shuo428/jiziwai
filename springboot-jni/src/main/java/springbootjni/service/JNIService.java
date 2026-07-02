package springbootjni.service;

import springbootjni.dto.jni.BridgeConnectRequest;
import springbootjni.dto.jni.BridgeStateResponse;
import springbootjni.dto.jni.ImageFrameResponse;
import springbootjni.dto.jni.TriggerCaptureResponse;

import java.util.List;

public interface JNIService {
    BridgeStateResponse getState();

    BridgeStateResponse connect(BridgeConnectRequest request);

    BridgeStateResponse disconnect();

    void sendReset();

    TriggerCaptureResponse sendTriggerOnce(Long userId, boolean autoProcess);

    void sendQueryStatus();

    void sendFullConfig(List<Integer> configBytes);

    void handleImageFrame(int width, int height, short[] pixels16, byte[] pixels8);

    void handleStatus(int statusBits, int errorCode);

    void handleConfigAck(int resultCode, int failedAddr);

    void handleTransportError(String channel, String message);

    List<ImageFrameResponse> listImages(Long userId);

    ImageFrameResponse processImage(Long userId, long imageId);

    boolean deleteImage(Long userId, long imageId);

    int clearImages(Long userId);
}
