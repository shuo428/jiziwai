package springbootjni.service;

import springbootjni.dto.jni.BridgeConnectRequest;
import springbootjni.dto.jni.BridgeStateResponse;
import springbootjni.dto.jni.CalibrationRequest;
import springbootjni.dto.jni.CalibrationGlobalSettingsRequest;
import springbootjni.dto.jni.CalibrationGlobalSettingsResponse;
import springbootjni.dto.jni.CalibrationPreviewResponse;
import springbootjni.dto.jni.CalibrationSessionResponse;
import springbootjni.dto.jni.ImageFrameResponse;
import springbootjni.dto.jni.ImagePixelDataResponse;
import springbootjni.dto.jni.MultiFrameAnalysisRequest;
import springbootjni.dto.jni.MultiFrameAnalysisResponse;
import springbootjni.dto.jni.SpectrumExtractionRequest;
import springbootjni.dto.jni.SpectrumExtractionResponse;
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

    ImagePixelDataResponse getImagePixels(Long userId,
                                          long imageId,
                                          String sourceMode,
                                          String displayFormat,
                                          boolean fullFrame,
                                          Integer xStart,
                                          Integer yStart,
                                          Integer width,
                                          Integer height);

    SpectrumExtractionResponse extractSpectrum(Long userId, long imageId, SpectrumExtractionRequest request);

    SpectrumExtractionResponse getLatestSpectrum(Long userId, long imageId);

    boolean deleteImage(Long userId, long imageId);

    int clearImages(Long userId);

    MultiFrameAnalysisResponse analyzeMultiFrame(Long userId, MultiFrameAnalysisRequest request);

    CalibrationSessionResponse generateCalibration(Long userId, String calibrationType, CalibrationRequest request);

    CalibrationSessionResponse buildCalibrationFromImages(Long userId, String calibrationType, CalibrationRequest request);

    List<CalibrationSessionResponse> listCalibrations(Long userId, String calibrationType);

    CalibrationSessionResponse getCalibration(Long userId, long sessionId);

    List<CalibrationPreviewResponse> listCalibrationPreviews(Long userId, long sessionId, int limit);

    CalibrationGlobalSettingsResponse getCalibrationGlobalSettings(Long userId);

    CalibrationGlobalSettingsResponse updateCalibrationGlobalSettings(
            Long userId,
            CalibrationGlobalSettingsRequest request);

}
