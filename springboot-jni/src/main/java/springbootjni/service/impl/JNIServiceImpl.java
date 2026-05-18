package springbootjni.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import springbootjni.dto.jni.BridgeConnectRequest;
import springbootjni.dto.jni.BridgeStateResponse;
import springbootjni.handler.WebSocketHandler;
import springbootjni.jni.BridgeListener;
import springbootjni.jni.SpectraBridgeNative;
import springbootjni.service.JNIService;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class JNIServiceImpl implements JNIService {
    private final WebSocketHandler webSocketHandler;
    private final ObjectMapper objectMapper;

    private final Object bridgeLock = new Object();
    private final BridgeListener bridgeListener = new BridgeListener() {
        @Override
        public void onImageFrame(int width, int height, short[] pixels16, byte[] pixels8) {
            JNIServiceImpl.this.handleImageFrame(width, height, pixels16, pixels8);
        }

        @Override
        public void onStatus(int statusBits, int errorCode) {
            JNIServiceImpl.this.handleStatus(statusBits, errorCode);
        }

        @Override
        public void onConfigAck(int resultCode, int failedAddr) {
            JNIServiceImpl.this.handleConfigAck(resultCode, failedAddr);
        }

        @Override
        public void onTransportError(String channel, String message) {
            JNIServiceImpl.this.handleTransportError(channel, message);
        }
    };

    private SpectraBridgeNative bridge;
    private boolean connected;
    private String host = "";
    private Integer controlPort;
    private Integer imagePort;
    private boolean verifyCrc = true;
    private String lastError;

    @Override
    public BridgeStateResponse getState() {
        synchronized (bridgeLock) {
            return toState(null);
        }
    }

    @Override
    public BridgeStateResponse connect(BridgeConnectRequest request) {
        validateConnectRequest(request);

        synchronized (bridgeLock) {
            closeBridgeQuietly();
            host = request.getHost().trim();
            controlPort = request.getControlPort();
            imagePort = request.getImagePort();
            verifyCrc = request.getVerifyCrc() == null || request.getVerifyCrc();
            lastError = null;

            try {
                bridge = new SpectraBridgeNative(bridgeListener);
                bridge.connect(host, controlPort, imagePort, verifyCrc);
                connected = true;
                BridgeStateResponse state = toState("连接成功");
                broadcastEvent("connection", state);
                return state;
            } catch (RuntimeException ex) {
                connected = false;
                lastError = ex.getMessage();
                closeBridgeQuietly();
                BridgeStateResponse failedState = toState("连接失败");
                broadcastEvent("connection", failedState);
                throw ex;
            }
        }
    }

    @Override
    public BridgeStateResponse disconnect() {
        synchronized (bridgeLock) {
            if (bridge != null && !bridge.isClosed()) {
                bridge.disconnect();
            }
            connected = false;
            closeBridgeQuietly();
            BridgeStateResponse state = toState("已断开连接");
            broadcastEvent("connection", state);
            return state;
        }
    }

    @Override
    public void sendReset() {
        requireConnectedNativeBridge().sendReset();
    }

    @Override
    public void sendTriggerOnce() {
        requireConnectedNativeBridge().sendTriggerOnce();
    }

    @Override
    public void sendQueryStatus() {
        requireConnectedNativeBridge().sendQueryStatus();
    }

    @Override
    public void sendFullConfig(List<Integer> configBytes) {
        if (configBytes == null) {
            throw new IllegalArgumentException("configBytes is required");
        }
        if (configBytes.size() != SpectraBridgeNative.FULL_CONFIG_SIZE) {
            throw new IllegalArgumentException(
                    "configBytes must contain exactly " + SpectraBridgeNative.FULL_CONFIG_SIZE + " bytes");
        }

        byte[] payload = new byte[SpectraBridgeNative.FULL_CONFIG_SIZE];
        for (int index = 0; index < configBytes.size(); index++) {
            Integer value = configBytes.get(index);
            if (value == null || value < 0 || value > 255) {
                throw new IllegalArgumentException("configBytes[" + index + "] must be in range 0..255");
            }
            payload[index] = (byte) (value & 0xFF);
        }

        requireConnectedNativeBridge().sendFullConfig(payload);
    }

    @Override
    public void handleImageFrame(int width, int height, short[] pixels16, byte[] pixels8) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("width", width);
        payload.put("height", height);
        payload.put("raw8Length", pixels8 == null ? 0 : pixels8.length);
        payload.put("raw16Length", pixels16 == null ? 0 : pixels16.length);
        payload.put("imageDataUrl", encodeImage(width, height, pixels16, pixels8));
        broadcastEvent("image_frame", payload);
    }

    @Override
    public void handleStatus(int statusBits, int errorCode) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("statusBits", statusBits);
        payload.put("statusBinary", toBinaryString(statusBits));
        payload.put("errorCode", errorCode);
        broadcastEvent("status", payload);
    }

    @Override
    public void handleConfigAck(int resultCode, int failedAddr) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("resultCode", resultCode);
        payload.put("failedAddr", failedAddr);
        broadcastEvent("config_ack", payload);
    }

    @Override
    public void handleTransportError(String channel, String message) {
        synchronized (bridgeLock) {
            lastError = message;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("channel", channel);
        payload.put("message", message);
        broadcastEvent("transport_error", payload);
    }

    private SpectraBridgeNative requireConnectedNativeBridge() {
        synchronized (bridgeLock) {
            if (!connected || bridge == null || bridge.isClosed()) {
                throw new IllegalStateException("Bridge is not connected");
            }
            return bridge;
        }
    }

    private void validateConnectRequest(BridgeConnectRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        if (request.getHost() == null || request.getHost().trim().isEmpty()) {
            throw new IllegalArgumentException("host is required");
        }
        if (request.getControlPort() == null || request.getControlPort() < 1 || request.getControlPort() > 65535) {
            throw new IllegalArgumentException("controlPort must be in range 1..65535");
        }
        if (request.getImagePort() == null || request.getImagePort() < 1 || request.getImagePort() > 65535) {
            throw new IllegalArgumentException("imagePort must be in range 1..65535");
        }
    }

    private void closeBridgeQuietly() {
        if (bridge == null) {
            return;
        }
        try {
            bridge.close();
        } catch (Exception ignored) {
        } finally {
            bridge = null;
        }
    }

    private BridgeStateResponse toState(String message) {
        BridgeStateResponse state = new BridgeStateResponse();
        state.setConnected(connected);
        state.setHost(host);
        state.setControlPort(controlPort);
        state.setImagePort(imagePort);
        state.setVerifyCrc(verifyCrc);
        state.setLastError(lastError);
        state.setMessage(message);
        return state;
    }

    private void broadcastEvent(String type, Object payload) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.put("timestamp", Instant.now().toString());
        event.put("payload", payload);
        try {
            webSocketHandler.broadcast(objectMapper.writeValueAsString(event));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize websocket event: " + type, ex);
        }
    }

    private String encodeImage(int width, int height, short[] pixels16, byte[] pixels8) {
        if (width <= 0 || height <= 0) {
            return "";
        }

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        byte[] buffer = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();

        if (pixels8 != null && pixels8.length >= buffer.length) {
            System.arraycopy(pixels8, 0, buffer, 0, buffer.length);
        } else if (pixels16 != null && pixels16.length >= buffer.length) {
            for (int index = 0; index < buffer.length; index++) {
                int rawValue = pixels16[index] & 0x0FFF;
                buffer[index] = (byte) ((rawValue * 255) / 4095);
            }
        } else {
            return "";
        }

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", outputStream);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(outputStream.toByteArray());
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to encode image frame", ex);
        }
    }

    private String toBinaryString(int statusBits) {
        String binary = Integer.toBinaryString(statusBits);
        if (binary.length() >= 32) {
            return binary;
        }
        return String.format("%32s", binary).replace(' ', '0');
    }
}
