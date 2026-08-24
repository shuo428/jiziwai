package springbootjni.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import springbootjni.dto.jni.BridgeConnectRequest;
import springbootjni.dto.jni.BridgeStateResponse;
import springbootjni.dto.jni.CalibrationRequest;
import springbootjni.dto.jni.CalibrationGlobalSettingsRequest;
import springbootjni.dto.jni.CalibrationGlobalSettingsResponse;
import springbootjni.dto.jni.CalibrationPreviewResponse;
import springbootjni.dto.jni.CalibrationSessionResponse;
import springbootjni.dto.jni.FpgaPayloadPixelDataResponse;
import springbootjni.dto.jni.ImageFrameResponse;
import springbootjni.dto.jni.ImagePixelDataResponse;
import springbootjni.dto.jni.MultiFrameAnalysisRequest;
import springbootjni.dto.jni.MultiFrameAnalysisResponse;
import springbootjni.dto.jni.SpectrumExtractionRequest;
import springbootjni.dto.jni.SpectrumExtractionResponse;
import springbootjni.dto.jni.TriggerCaptureResponse;
import springbootjni.handler.WebSocketHandler;
import springbootjni.jni.BridgeListener;
import springbootjni.jni.SpectraBridgeNative;
import springbootjni.service.JNIService;
import springbootjni.service.SpectralImagePersistenceService;
import springbootjni.service.SpectralCalibrationService;
import springbootjni.service.SpectralMultiFrameQualityAnalysisService;
import springbootjni.service.SpectralSpectrumExtractionService;

import javax.annotation.PreDestroy;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * JNI桥接与单帧采集事务的业务实现。
 *
 * <p>这里刻意把“发送一次触发”和“等待异步图片”视为同一个事务：</p>
 * <ol>
 *     <li>发送前先在数据库创建WAITING采集记录；</li>
 *     <li>同一时刻只允许一个未完成触发，避免没有frameId时无法对应回调；</li>
 *     <li>收到通过native完整性校验的图片后落盘并写数据库；</li>
 *     <li>FPGA错误、native校验失败或超时都会写入完整性失败记录；</li>
 *     <li>超时后关闭连接，防止迟到图片被下一次触发错误接收。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class JNIServiceImpl implements JNIService {
    private static final int DEFAULT_EXPECTED_WIDTH = 800;
    private static final int DEFAULT_EXPECTED_HEIGHT = 600;
    private static final String PIXEL_FORMAT_RAW16_LOW12 = "RAW16_LOW12";
    private static final String READOUT_ORDER_GLUX1605_HDR_4LANE =
            "GLUX1605_HDR_4LANE_INTERLEAVED_EFFECTIVE";
    private static final String READOUT_ORDER_ROW_MAJOR = "ROW_MAJOR";

    private final WebSocketHandler webSocketHandler;
    private final ObjectMapper objectMapper;
    private final SpectralImagePersistenceService persistenceService;
    private final SpectralSpectrumExtractionService spectrumExtractionService;
    private final SpectralCalibrationService calibrationService;

    /** 保护native桥对象和连接状态。 */
    private final Object bridgeLock = new Object();

    /** 保护当前唯一的单帧采集事务。 */
    private final Object captureLock = new Object();

    /**
     * 单线程调度器只用于单帧超时，不执行图像处理。
     * 使用守护线程，避免开发环境热重启时阻止JVM退出。
     */
    private final ScheduledExecutorService captureTimeoutExecutor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "spectral-capture-timeout");
                thread.setDaemon(true);
                return thread;
            });

    @Value("${spectral.capture.timeout-ms:15000}")
    private long captureTimeoutMs;

    private final BridgeListener bridgeListener = new BridgeListener() {
        @Override
        public void onImageFrame(int width, int height, short[] pixels16, byte[] pixels8, byte[] fpgaPayload) {
            JNIServiceImpl.this.handleImageFrame(width, height, pixels16, pixels8, fpgaPayload);
        }

        @Override
        public void onHdrImageFrame(int width, int height, short[] hgPixels16, short[] lgPixels16, byte[] fpgaPayload) {
            JNIServiceImpl.this.handleHdrImageFrame(width, height, hgPixels16, lgPixels16, fpgaPayload);
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
    private int expectedWidth = DEFAULT_EXPECTED_WIDTH;
    private int expectedHeight = DEFAULT_EXPECTED_HEIGHT;
    private String pixelFormat = PIXEL_FORMAT_RAW16_LOW12;
    private String readoutOrder = READOUT_ORDER_GLUX1605_HDR_4LANE;
    private String lastError;
    private PendingCapture pendingCapture;

    @Override
    public BridgeStateResponse getState() {
        synchronized (bridgeLock) {
            return toState(null);
        }
    }

    @Override
    public BridgeStateResponse connect(BridgeConnectRequest request) {
        validateConnectRequest(request);

        synchronized (captureLock) {
            if (pendingCapture != null) {
                throw new IllegalStateException("仍有一笔采集正在等待图片，不能重新连接");
            }
        }

        synchronized (bridgeLock) {
            closeBridgeQuietly();
            host = request.getHost().trim();
            controlPort = request.getControlPort();
            imagePort = request.getImagePort();
            verifyCrc = request.getVerifyCrc() == null || request.getVerifyCrc();
            expectedWidth = request.getExpectedWidth();
            expectedHeight = request.getExpectedHeight();
            pixelFormat = normalizePixelFormat(request.getPixelFormat());
            readoutOrder = normalizeReadoutOrder(request.getReadoutOrder());
            lastError = null;

            try {
                bridge = new SpectraBridgeNative(bridgeListener);
                bridge.connect(
                        host,
                        controlPort,
                        imagePort,
                        verifyCrc,
                        expectedWidth,
                        expectedHeight,
                        pixelFormat,
                        readoutOrder);
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
        PendingCapture capture = takePendingCapture();
        if (capture != null) {
            persistenceService.saveFailedCapture(
                    capture.captureId,
                    "FAILED",
                    "DISCONNECTED",
                    "等待图片期间设备被断开",
                    null,
                    capture.elapsedMs(),
                    Collections.singletonMap("requestId", capture.requestId));
        }

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

    /**
     * 创建数据库采集事务后发送单次触发。
     *
     * <p>由于当前协议没有frameId，pendingCapture只能有一个。这个约束比允许并发后
     * 靠到达顺序猜测所属请求更可靠。</p>
     */
    @Override
    public TriggerCaptureResponse sendTriggerOnce(Long userId, boolean autoProcess, String captureScene) {
        SpectraBridgeNative nativeBridge = requireConnectedNativeBridge();
        String requestId = UUID.randomUUID().toString();
        String normalizedCaptureScene = normalizeCaptureScene(captureScene);
        boolean calibrationScene = isCalibrationScene(normalizedCaptureScene);
        boolean effectiveAutoProcess = calibrationScene ? false : autoProcess;

        Map<String, Object> configSnapshot = new LinkedHashMap<>();
        configSnapshot.put("host", host);
        configSnapshot.put("controlPort", controlPort);
        configSnapshot.put("imagePort", imagePort);
        configSnapshot.put("verifyCrc", verifyCrc);
        configSnapshot.put("autoProcess", effectiveAutoProcess);
        configSnapshot.put("captureScene", normalizedCaptureScene);
        if (calibrationScene) {
            configSnapshot.put("calibrationCapturePolicy", "skip active calibration package and image processing");
        }
        if (isHdrCaptureScene(normalizedCaptureScene)) {
            configSnapshot.put("hdrPayloadLayout", "HG_FULL_FRAME_THEN_LG_FULL_FRAME");
            if ("HDR_DARK".equals(normalizedCaptureScene)) {
                configSnapshot.put("hdrCalibrationCapturePolicy",
                        "capture HG/LG dark frames; keep plane data and diagnostic composite, do not use as spectral HDR fusion");
            } else if ("HDR_FLAT".equals(normalizedCaptureScene)) {
                configSnapshot.put("hdrCalibrationCapturePolicy",
                        "capture HG/LG flat frames; keep plane data and diagnostic composite, do not use as spectral HDR fusion");
            } else {
                configSnapshot.put("hdrFusionPolicy",
                        "median HG/LG gain ratio estimation with saturation-aware HG/LG blending");
            }
        }
        configSnapshot.put("expectedWidth", expectedWidth);
        configSnapshot.put("expectedHeight", expectedHeight);
        configSnapshot.put("pixelFormat", pixelFormat);
        configSnapshot.put("readoutOrder", readoutOrder);

        synchronized (captureLock) {
            if (pendingCapture != null) {
                throw new IllegalStateException("已有一笔单帧采集正在等待回调");
            }

            long captureId = persistenceService.createCapture(userId, requestId, normalizedCaptureScene, configSnapshot);
            PendingCapture capture = new PendingCapture(
                    userId,
                    captureId,
                    requestId,
                    verifyCrc,
                    effectiveAutoProcess,
                    normalizedCaptureScene,
                    expectedWidth,
                    expectedHeight,
                    pixelFormat,
                    readoutOrder);
            pendingCapture = capture;

            capture.timeoutFuture = captureTimeoutExecutor.schedule(
                    () -> handleCaptureTimeout(captureId),
                    captureTimeoutMs,
                    TimeUnit.MILLISECONDS);

            try {
                nativeBridge.sendTriggerOnce();
            } catch (RuntimeException ex) {
                pendingCapture = null;
                capture.cancelTimeout();
                persistenceService.saveFailedCapture(
                        captureId,
                        "FAILED",
                        "TRIGGER_SEND_FAILED",
                        ex.getMessage(),
                        null,
                        capture.elapsedMs(),
                        capture.failureDetails());
                throw ex;
            }

            return new TriggerCaptureResponse(captureId, requestId);
        }
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

    /**
     * native只会把完整性校验通过的帧送到这里。
     * 回调线程来自C++，因此先原子地取走pendingCapture，再执行较慢的文件和数据库写入。
     */
    @Override
    public void handleImageFrame(int width, int height, short[] pixels16, byte[] pixels8, byte[] fpgaPayload) {
        PendingCapture capture = takePendingCapture();
        if (capture == null) {
            // 没有等待事务时收到图片，说明它可能是超时后的迟到帧。
            // 在没有frameId的协议中不能安全归属，因此明确丢弃。
            Map<String, Object> orphan = new LinkedHashMap<>();
            orphan.put("channel", "image");
            orphan.put("message", "收到无法归属到采集请求的迟到图片，已丢弃");
            broadcastEvent("transport_error", orphan);
            return;
        }

        try {
            if (isHdrCaptureScene(capture.captureScene)) {
                String message = "当前采集事务期望HDR双平面图像，但native返回的是普通单平面图像";
                persistenceService.saveFailedCapture(
                        capture.captureId,
                        "FAILED",
                        "CAPTURE_SCENE_MISMATCH",
                        message,
                        null,
                        capture.elapsedMs(),
                        capture.failureDetails());
                broadcastCaptureFailure(capture, "CAPTURE_SCENE_MISMATCH", message);
                return;
            }

            ImageFrameResponse frame = persistenceService.saveSuccessfulFrame(
                    capture.captureId,
                    capture.userId,
                    capture.requestId,
                    width,
                    height,
                    pixels16,
                    pixels8,
                    fpgaPayload,
                    capture.pixelFormat,
                    capture.readoutOrder,
                    capture.verifyCrc,
                    capture.autoProcess,
                    capture.captureScene,
                    capture.elapsedMs());
            broadcastEvent("image_frame", frame);
        } catch (RuntimeException ex) {
            persistenceService.saveFailedCapture(
                    capture.captureId,
                    "FAILED",
                    "PERSISTENCE_FAILED",
                    ex.getMessage(),
                    null,
                    capture.elapsedMs(),
                    capture.failureDetails());
            broadcastCaptureFailure(capture, "PERSISTENCE_FAILED", ex.getMessage());
        }
    }

    @Override
    public void handleHdrImageFrame(int width, int height, short[] hgPixels16, short[] lgPixels16, byte[] fpgaPayload) {
        PendingCapture capture = takePendingCapture();
        if (capture == null) {
            Map<String, Object> orphan = new LinkedHashMap<>();
            orphan.put("channel", "image");
            orphan.put("message", "收到无法归属到采集请求的迟到HDR图片，已丢弃");
            broadcastEvent("transport_error", orphan);
            return;
        }

        try {
            if (!isHdrCaptureScene(capture.captureScene)) {
                String message = "当前采集事务期望普通/校准图像，但native返回的是HDR双增益图像";
                persistenceService.saveFailedCapture(
                        capture.captureId,
                        "FAILED",
                        "CAPTURE_SCENE_MISMATCH",
                        message,
                        null,
                        capture.elapsedMs(),
                        capture.failureDetails());
                broadcastCaptureFailure(capture, "CAPTURE_SCENE_MISMATCH", message);
                return;
            }

            ImageFrameResponse frame = persistenceService.saveSuccessfulHdrFrame(
                    capture.captureId,
                    capture.userId,
                    capture.requestId,
                    width,
                    height,
                    hgPixels16,
                    lgPixels16,
                    fpgaPayload,
                    capture.pixelFormat,
                    capture.readoutOrder,
                    capture.verifyCrc,
                    capture.autoProcess,
                    capture.captureScene,
                    capture.elapsedMs());
            broadcastEvent("image_frame", frame);
        } catch (RuntimeException ex) {
            persistenceService.saveFailedCapture(
                    capture.captureId,
                    "FAILED",
                    "PERSISTENCE_FAILED",
                    ex.getMessage(),
                    null,
                    capture.elapsedMs(),
                    capture.failureDetails());
            broadcastCaptureFailure(capture, "PERSISTENCE_FAILED", ex.getMessage());
        }
    }

    @Override
    public void handleStatus(int statusBits, int errorCode) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("statusBits", statusBits);
        payload.put("statusBinary", toBinaryString(statusBits));
        payload.put("errorCode", errorCode);
        broadcastEvent("status", payload);

        // FPGA明确返回非零错误码时，本次采集立即失败，不必继续等到15秒超时。
        if (errorCode != 0) {
            PendingCapture capture = takePendingCapture();
            if (capture != null) {
                String message = "FPGA返回采集错误码: " + errorCode;
                persistenceService.saveFailedCapture(
                        capture.captureId,
                        "FAILED",
                        "FPGA_ERROR",
                        message,
                        errorCode,
                        capture.elapsedMs(),
                        Collections.singletonMap("statusBits", statusBits));
                broadcastCaptureFailure(capture, "FPGA_ERROR", message);
            }
        }
    }

    @Override
    public void handleConfigAck(int resultCode, int failedAddr) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("resultCode", resultCode);
        payload.put("failedAddr", failedAddr);
        broadcastEvent("config_ack", payload);
    }

    /**
     * C++完整性错误和TCP错误统一从这里到达。
     * C++消息格式为“[稳定错误码] 详细说明”，解析后直接写入数据库result_code。
     */
    @Override
    public void handleTransportError(String channel, String message) {
        String resultCode = extractResultCode(message);
        PendingCapture capture = takePendingCapture();
        if (capture != null) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("channel", channel);
            details.put("nativeMessage", message);
            persistenceService.saveFailedCapture(
                    capture.captureId,
                    "FAILED",
                    resultCode,
                    message,
                    null,
                    capture.elapsedMs(),
                    details);
            broadcastCaptureFailure(capture, resultCode, message);
        }

        synchronized (bridgeLock) {
            lastError = message;
            connected = false;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("channel", channel);
        payload.put("code", resultCode);
        payload.put("message", message);
        broadcastEvent("transport_error", payload);
        broadcastEvent("connection", getState());
    }

    @Override
    public List<ImageFrameResponse> listImages(Long userId) {
        return persistenceService.listImages(userId);
    }

    @Override
    public List<ImageFrameResponse> listHdrImages(Long userId) {
        return persistenceService.listHdrImages(userId);
    }

    @Override
    public List<ImageFrameResponse> listHdrDarkImages(Long userId) {
        return persistenceService.listHdrDarkImages(userId);
    }

    @Override
    public List<ImageFrameResponse> listHdrFlatImages(Long userId) {
        return persistenceService.listHdrFlatImages(userId);
    }

    private String normalizeCaptureScene(String captureScene) {
        if (captureScene == null || captureScene.trim().isEmpty()) {
            return "NORMAL";
        }
        String scene = captureScene.trim().toUpperCase(java.util.Locale.ROOT);
        if ("CALIBRATION_DARK".equals(scene)) {
            return "DARK";
        }
        if ("CALIBRATION_FLAT".equals(scene)) {
            return "FLAT";
        }
        if ("CALIBRATION_HDR_DARK".equals(scene) || "HDR_CALIBRATION_DARK".equals(scene)) {
            return "HDR_DARK";
        }
        if ("CALIBRATION_HDR_FLAT".equals(scene) || "HDR_CALIBRATION_FLAT".equals(scene)) {
            return "HDR_FLAT";
        }
        if ("NORMAL".equals(scene)
                || "DARK".equals(scene)
                || "FLAT".equals(scene)
                || "HDR".equals(scene)
                || "HDR_DARK".equals(scene)
                || "HDR_FLAT".equals(scene)) {
            return scene;
        }
        throw new IllegalArgumentException("captureScene 只能是 NORMAL、DARK、FLAT、HDR、HDR_DARK 或 HDR_FLAT");
    }

    private boolean isCalibrationScene(String captureScene) {
        return "DARK".equals(captureScene)
                || "FLAT".equals(captureScene)
                || "HDR_DARK".equals(captureScene)
                || "HDR_FLAT".equals(captureScene);
    }

    private boolean isHdrCaptureScene(String captureScene) {
        String scene = normalizeCaptureScene(captureScene);
        return "HDR".equals(scene) || "HDR_DARK".equals(scene) || "HDR_FLAT".equals(scene);
    }

    @Override
    public ImageFrameResponse processImage(Long userId, long imageId) {
        ensureNoPendingCaptureForHistoryMutation();
        return persistenceService.processImage(userId, imageId);
    }

    @Override
    public ImagePixelDataResponse getImagePixels(Long userId,
                                                 long imageId,
                                                 String sourceMode,
                                                 String displayFormat,
                                                 boolean fullFrame,
                                                 Integer xStart,
                                                 Integer yStart,
                                                 Integer width,
                                                 Integer height) {
        return persistenceService.getImagePixels(
                userId,
                imageId,
                sourceMode,
                displayFormat,
                fullFrame,
                xStart,
                yStart,
                width,
                height);
    }

    @Override
    public FpgaPayloadPixelDataResponse getFpgaPayloadPixels(Long userId,
                                                             long imageId,
                                                             Integer start,
                                                             Integer count,
                                                             boolean fullFrame) {
        return persistenceService.getFpgaPayloadPixels(userId, imageId, start, count, fullFrame);
    }

    @Override
    public SpectrumExtractionResponse extractSpectrum(Long userId,
                                                      long imageId,
                                                      SpectrumExtractionRequest request) {
        ensureNoPendingCaptureForHistoryMutation();
        return spectrumExtractionService.extract(userId, imageId, request);
    }

    @Override
    public SpectrumExtractionResponse getLatestSpectrum(Long userId, long imageId) {
        return spectrumExtractionService.getLatest(userId, imageId);
    }

    @Override
    public boolean deleteImage(Long userId, long imageId) {
        ensureNoPendingCaptureForHistoryMutation();
        return persistenceService.deleteImage(userId, imageId);
    }

    @Override
    public int clearImages(Long userId) {
        ensureNoPendingCaptureForHistoryMutation();
        return persistenceService.clearImages(userId);
    }

    @Override
    public MultiFrameAnalysisResponse analyzeMultiFrame(Long userId, MultiFrameAnalysisRequest request) {
        ensureNoPendingCaptureForHistoryMutation();
        if (request == null) {
            throw new IllegalArgumentException("多帧检测请求不能为空");
        }
        return persistenceService.analyzeMultiFrame(
                userId,
                request.getImageIds(),
                Boolean.TRUE.equals(request.getRepair()),
                request.getVoteRatio() == null ? 0.6d : request.getVoteRatio());
    }

    @Override
    public CalibrationSessionResponse generateCalibration(Long userId,
                                                          String calibrationType,
                                                          CalibrationRequest request) {
        ensureNoPendingCaptureForHistoryMutation();
        return calibrationService.generateSimulated(userId, calibrationType, request);
    }

    @Override
    public CalibrationSessionResponse buildCalibrationFromImages(Long userId,
                                                                  String calibrationType,
                                                                  CalibrationRequest request) {
        ensureNoPendingCaptureForHistoryMutation();
        return calibrationService.buildFromImages(userId, calibrationType, request);
    }

    @Override
    public List<CalibrationSessionResponse> listCalibrations(Long userId, String calibrationType) {
        return calibrationService.list(userId, calibrationType);
    }

    @Override
    public CalibrationSessionResponse getCalibration(Long userId, long sessionId) {
        return calibrationService.get(userId, sessionId);
    }

    @Override
    public List<CalibrationPreviewResponse> listCalibrationPreviews(Long userId,
                                                                      long sessionId,
                                                                      int limit) {
        return calibrationService.listPreviews(userId, sessionId, limit);
    }

    @Override
    public CalibrationPreviewResponse getCalibrationReferencePreview(Long userId, long sessionId) {
        return calibrationService.getReferencePreview(userId, sessionId);
    }

    @Override
    public List<CalibrationPreviewResponse> listCalibrationReferencePreviews(Long userId, long sessionId) {
        return calibrationService.listReferencePreviews(userId, sessionId);
    }

    @Override
    public boolean deleteCalibration(Long userId, long sessionId) {
        ensureNoPendingCaptureForHistoryMutation();
        return calibrationService.delete(userId, sessionId);
    }

    @Override
    public CalibrationGlobalSettingsResponse getCalibrationGlobalSettings(Long userId) {
        return calibrationService.getGlobalSettings(userId);
    }

    @Override
    public CalibrationGlobalSettingsResponse updateCalibrationGlobalSettings(
            Long userId,
            CalibrationGlobalSettingsRequest request) {
        ensureNoPendingCaptureForHistoryMutation();
        return calibrationService.updateGlobalSettings(userId, request);
    }

    /**
     * 防止用户在一帧仍处于WAITING时删除其采集主记录。
     * 否则图片回调到达后，外键目标已经不存在，会把一个正常帧变成数据库写入失败。
     */
    private void ensureNoPendingCaptureForHistoryMutation() {
        synchronized (captureLock) {
            if (pendingCapture != null) {
                throw new IllegalStateException("图像正在采集中，请等待本次采集结束后再删除历史记录");
            }
        }
    }

    private void handleCaptureTimeout(long captureId) {
        PendingCapture capture;
        synchronized (captureLock) {
            if (pendingCapture == null || pendingCapture.captureId != captureId) {
                return;
            }
            capture = pendingCapture;
            pendingCapture = null;
        }

        String message = "等待图像帧超时（" + captureTimeoutMs + " ms）";
        persistenceService.saveFailedCapture(
                capture.captureId,
                "TIMEOUT",
                "IMAGE_TIMEOUT",
                message,
                null,
                capture.elapsedMs(),
                Collections.singletonMap("requestId", capture.requestId));
        broadcastCaptureFailure(capture, "IMAGE_TIMEOUT", message);

        // 关闭socket会解除C++阻塞中的recv。更重要的是，下一次触发必须重新连接，
        // 从物理连接层清掉可能迟到的旧帧，避免没有frameId时发生错误归属。
        synchronized (bridgeLock) {
            connected = false;
            lastError = message;
            closeBridgeQuietly();
            broadcastEvent("connection", toState("采集超时，连接已关闭，请重新连接"));
        }
    }

    private PendingCapture takePendingCapture() {
        synchronized (captureLock) {
            PendingCapture capture = pendingCapture;
            pendingCapture = null;
            if (capture != null) {
                capture.cancelTimeout();
            }
            return capture;
        }
    }

    private void broadcastCaptureFailure(PendingCapture capture, String code, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("captureId", capture.captureId);
        payload.put("requestId", capture.requestId);
        payload.put("code", code);
        payload.put("message", message);
        broadcastEvent("capture_failed", payload);
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
        int requestedWidth = request.getExpectedWidth() == null
                ? DEFAULT_EXPECTED_WIDTH
                : request.getExpectedWidth();
        int requestedHeight = request.getExpectedHeight() == null
                ? DEFAULT_EXPECTED_HEIGHT
                : request.getExpectedHeight();
        if (requestedWidth < 1 || requestedHeight < 1) {
            throw new IllegalArgumentException("expectedWidth and expectedHeight must be greater than zero");
        }
        String normalizedPixelFormat = normalizePixelFormat(request.getPixelFormat());
        if (!PIXEL_FORMAT_RAW16_LOW12.equals(normalizedPixelFormat)) {
            throw new IllegalArgumentException("当前仅支持 RAW16_LOW12 像素格式");
        }
        String normalizedReadoutOrder = normalizeReadoutOrder(request.getReadoutOrder());
        if (READOUT_ORDER_GLUX1605_HDR_4LANE.equals(normalizedReadoutOrder)
                && requestedWidth % 4 != 0) {
            throw new IllegalArgumentException("GLUX1605 HDR 4-lane 读出要求 expectedWidth 能被 4 整除");
        }
        request.setExpectedWidth(requestedWidth);
        request.setExpectedHeight(requestedHeight);
        request.setPixelFormat(normalizedPixelFormat);
        request.setReadoutOrder(normalizedReadoutOrder);
    }

    private String normalizePixelFormat(String value) {
        if (value == null || value.trim().isEmpty()) {
            return PIXEL_FORMAT_RAW16_LOW12;
        }
        return value.trim().toUpperCase();
    }

    private String normalizeReadoutOrder(String value) {
        if (value == null || value.trim().isEmpty()) {
            return READOUT_ORDER_GLUX1605_HDR_4LANE;
        }
        String normalized = value.trim().toUpperCase();
        if (READOUT_ORDER_GLUX1605_HDR_4LANE.equals(normalized)
                || READOUT_ORDER_ROW_MAJOR.equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("readoutOrder 只能是 GLUX1605_HDR_4LANE_INTERLEAVED_EFFECTIVE 或 ROW_MAJOR");
    }

    private void closeBridgeQuietly() {
        if (bridge == null) {
            return;
        }
        try {
            bridge.close();
        } catch (Exception ignored) {
            // 关闭发生在清理路径，不用二次覆盖最初的业务错误。
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
        state.setExpectedWidth(expectedWidth);
        state.setExpectedHeight(expectedHeight);
        state.setPixelFormat(pixelFormat);
        state.setReadoutOrder(readoutOrder);
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

    private String extractResultCode(String message) {
        if (message != null && message.startsWith("[")) {
            int closingBracket = message.indexOf(']');
            if (closingBracket > 1) {
                return message.substring(1, closingBracket);
            }
        }
        return "TRANSPORT_ERROR";
    }

    private String toBinaryString(int statusBits) {
        String binary = Integer.toBinaryString(statusBits);
        if (binary.length() >= 32) {
            return binary;
        }
        return String.format("%32s", binary).replace(' ', '0');
    }

    @PreDestroy
    public void shutdownCaptureTimeoutExecutor() {
        captureTimeoutExecutor.shutdownNow();
    }

    private static final class PendingCapture {
        private final Long userId;
        private final long captureId;
        private final String requestId;
        private final boolean verifyCrc;
        private final boolean autoProcess;
        private final String captureScene;
        private final int expectedWidth;
        private final int expectedHeight;
        private final String pixelFormat;
        private final String readoutOrder;
        private final long startedNanos = System.nanoTime();
        private ScheduledFuture<?> timeoutFuture;

        private PendingCapture(Long userId,
                               long captureId,
                               String requestId,
                               boolean verifyCrc,
                               boolean autoProcess,
                               String captureScene,
                               int expectedWidth,
                               int expectedHeight,
                               String pixelFormat,
                               String readoutOrder) {
            this.userId = userId;
            this.captureId = captureId;
            this.requestId = requestId;
            this.verifyCrc = verifyCrc;
            this.autoProcess = autoProcess;
            this.captureScene = captureScene == null ? "NORMAL" : captureScene;
            this.expectedWidth = expectedWidth;
            this.expectedHeight = expectedHeight;
            this.pixelFormat = pixelFormat;
            this.readoutOrder = readoutOrder;
        }

        private long elapsedMs() {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        }

        private void cancelTimeout() {
            if (timeoutFuture != null) {
                timeoutFuture.cancel(false);
            }
        }

        private Map<String, Object> failureDetails() {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("requestId", requestId);
            details.put("autoProcess", autoProcess);
            details.put("captureScene", captureScene);
            details.put("expectedWidth", expectedWidth);
            details.put("expectedHeight", expectedHeight);
            details.put("pixelFormat", pixelFormat);
            details.put("readoutOrder", readoutOrder);
            return details;
        }
    }
}
