package springbootjni.controller;

import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import springbootjni.dto.ApiResponse;
import springbootjni.dto.jni.BridgeConnectRequest;
import springbootjni.dto.jni.BridgeFullConfigRequest;
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
import springbootjni.dto.jni.TriggerCaptureRequest;
import springbootjni.dto.jni.TriggerCaptureResponse;
import springbootjni.service.JNIService;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/jni")
public class JNIController {
    private final JNIService jniService;

    @GetMapping("/state")
    public ApiResponse<BridgeStateResponse> getState() {
        try {
            return ApiResponse.success("JNI bridge state fetched successfully", jniService.getState());
        } catch (Exception e) {
            return ApiResponse.error("Failed to fetch JNI bridge state: " + e.getMessage());
        }
    }

    @PostMapping("/connect")
    public ApiResponse<BridgeStateResponse> connect(@RequestBody BridgeConnectRequest request) {
        try {
            BridgeStateResponse state = jniService.connect(request);
            return ApiResponse.success("JNI bridge connected successfully", state);
        } catch (Exception e) {
            return ApiResponse.error("Failed to connect JNI bridge: " + e.getMessage());
        }
    }

    @PostMapping("/disconnect")
    public ApiResponse<BridgeStateResponse> disconnect() {
        try {
            BridgeStateResponse state = jniService.disconnect();
            return ApiResponse.success("JNI bridge disconnected successfully", state);
        } catch (Exception e) {
            return ApiResponse.error("Failed to disconnect JNI bridge: " + e.getMessage());
        }
    }

    @PostMapping("/commands/reset")
    public ApiResponse<String> sendReset() {
        try {
            jniService.sendReset();
            return ApiResponse.success("Reset command sent successfully");
        } catch (Exception e) {
            return ApiResponse.error("Failed to send reset command: " + e.getMessage());
        }
    }

    @PostMapping("/commands/trigger-once")
    public ApiResponse<TriggerCaptureResponse> sendTriggerOnce(
            @RequestBody(required = false) TriggerCaptureRequest request) {
        try {
            Long userId = StpUtil.getLoginIdAsLong();
            boolean autoProcess = request != null && Boolean.TRUE.equals(request.getAutoProcess());
            String captureScene = request == null ? null : request.getCaptureScene();
            TriggerCaptureResponse response = jniService.sendTriggerOnce(userId, autoProcess, captureScene);
            return ApiResponse.success("Trigger-once command sent successfully", response);
        } catch (Exception e) {
            return ApiResponse.error("Failed to send trigger-once command: " + e.getMessage());
        }
    }

    /**
     * 从PostgreSQL和磁盘加载当前用户的历史图片。
     * 图片不再由浏览器localStorage提供，因此刷新页面或更换浏览器后仍可查询。
     */
    @GetMapping("/images")
    public ApiResponse<List<ImageFrameResponse>> listImages() {
        try {
            return ApiResponse.success(jniService.listImages(StpUtil.getLoginIdAsLong()));
        } catch (Exception e) {
            return ApiResponse.error("Failed to list images: " + e.getMessage());
        }
    }

    /**
     * 查询当前用户最近的 HDR 双增益采集记录。
     */
    @GetMapping("/hdr/images")
    public ApiResponse<List<ImageFrameResponse>> listHdrImages() {
        try {
            return ApiResponse.success(jniService.listHdrImages(StpUtil.getLoginIdAsLong()));
        } catch (Exception e) {
            return ApiResponse.error("Failed to list HDR images: " + e.getMessage());
        }
    }

    /**
     * 查询当前用户最近的 HDR 暗场双平面校准样本。
     */
    @GetMapping("/hdr-dark/images")
    public ApiResponse<List<ImageFrameResponse>> listHdrDarkImages() {
        try {
            return ApiResponse.success(jniService.listHdrDarkImages(StpUtil.getLoginIdAsLong()));
        } catch (Exception e) {
            return ApiResponse.error("Failed to list HDR dark images: " + e.getMessage());
        }
    }

    /**
     * 查询当前用户最近的 HDR 平场双平面校准样本。
     */
    @GetMapping("/hdr-flat/images")
    public ApiResponse<List<ImageFrameResponse>> listHdrFlatImages() {
        try {
            return ApiResponse.success(jniService.listHdrFlatImages(StpUtil.getLoginIdAsLong()));
        } catch (Exception e) {
            return ApiResponse.error("Failed to list HDR flat images: " + e.getMessage());
        }
    }

    /**
     * 对历史图片执行当前阶段可用的图像处理：坏点插值、少量异常行/列校正和处理后复检。
     */
    @PostMapping("/images/{imageId}/process")
    public ApiResponse<ImageFrameResponse> processImage(@PathVariable long imageId) {
        try {
            ImageFrameResponse frame = jniService.processImage(StpUtil.getLoginIdAsLong(), imageId);
            return ApiResponse.success("Image processed successfully", frame);
        } catch (Exception e) {
            return ApiResponse.error("Failed to process image: " + e.getMessage());
        }
    }

    /**
     * 按需读取重排后 RAW16 像素窗口。
     *
     * <p>这里的 ORIGINAL 表示“未做校准/处理的原图版本”，但它已经不是 FPGA 直接输出的
     * lane 交织 payload，而是 native 按芯片读出顺序转成正常行列坐标后的 RAW16。</p>
     */
    @GetMapping("/images/{imageId}/pixels")
    public ApiResponse<ImagePixelDataResponse> getImagePixels(@PathVariable long imageId,
                                                              @RequestParam(defaultValue = "ORIGINAL") String source,
                                                              @RequestParam(defaultValue = "DN") String format,
                                                              @RequestParam(defaultValue = "false") boolean fullFrame,
                                                              @RequestParam(required = false) Integer xStart,
                                                              @RequestParam(required = false) Integer yStart,
                                                              @RequestParam(required = false) Integer width,
                                                              @RequestParam(required = false) Integer height) {
        try {
            ImagePixelDataResponse pixels = jniService.getImagePixels(
                    StpUtil.getLoginIdAsLong(),
                    imageId,
                    source,
                    format,
                    fullFrame,
                    xStart,
                    yStart,
                    width,
                    height);
            return ApiResponse.success("Image pixels loaded successfully", pixels);
        } catch (Exception e) {
            return ApiResponse.error("Failed to load image pixels: " + e.getMessage());
        }
    }

    /**
     * 按需读取 FPGA 直接输出的原始有效像素 payload。
     *
     * <p>这里返回的是 fpga_payload.bin 的线性顺序，并给出每个 payload 像素重排后对应的
     * 正常图像坐标，用于闭环验证 GLUX1605BSI HDR 4-lane 转序是否正确。</p>
     */
    @GetMapping("/images/{imageId}/fpga-payload")
    public ApiResponse<FpgaPayloadPixelDataResponse> getFpgaPayloadPixels(@PathVariable long imageId,
                                                                          @RequestParam(required = false) Integer start,
                                                                          @RequestParam(required = false) Integer count,
                                                                          @RequestParam(defaultValue = "false") boolean fullFrame) {
        try {
            FpgaPayloadPixelDataResponse pixels = jniService.getFpgaPayloadPixels(
                    StpUtil.getLoginIdAsLong(),
                    imageId,
                    start,
                    count,
                    fullFrame);
            return ApiResponse.success("FPGA payload pixels loaded successfully", pixels);
        } catch (Exception e) {
            return ApiResponse.error("Failed to load FPGA payload pixels: " + e.getMessage());
        }
    }

    /**
     * 从 PASS 图像提取一维像素域光谱。第一版输出 pixelIndex-intensity，不做 nm 波长标定。
     */
    @PostMapping("/images/{imageId}/spectrum/extract")
    public ApiResponse<SpectrumExtractionResponse> extractSpectrum(
            @PathVariable long imageId,
            @RequestBody(required = false) SpectrumExtractionRequest request) {
        try {
            SpectrumExtractionResponse spectrum = jniService.extractSpectrum(
                    StpUtil.getLoginIdAsLong(),
                    imageId,
                    request);
            return ApiResponse.success("Spectrum extracted successfully", spectrum);
        } catch (Exception e) {
            return ApiResponse.error("Failed to extract spectrum: " + e.getMessage());
        }
    }

    /**
     * 读取当前图片最近一次一维光谱提取结果；没有提取过时返回null。
     */
    @GetMapping("/images/{imageId}/spectrum")
    public ApiResponse<SpectrumExtractionResponse> getLatestSpectrum(@PathVariable long imageId) {
        try {
            SpectrumExtractionResponse spectrum = jniService.getLatestSpectrum(
                    StpUtil.getLoginIdAsLong(),
                    imageId);
            return ApiResponse.success("Latest spectrum fetched successfully", spectrum);
        } catch (Exception e) {
            return ApiResponse.error("Failed to fetch latest spectrum: " + e.getMessage());
        }
    }

    /**
     * 删除一张图片时同时删除数据库采集记录及磁盘上的原始图、预览图。
     */
    @DeleteMapping("/images/{imageId}")
    public ApiResponse<Boolean> deleteImage(@PathVariable long imageId) {
        try {
            boolean deleted = jniService.deleteImage(StpUtil.getLoginIdAsLong(), imageId);
            return ApiResponse.success(deleted ? "Image deleted successfully" : "Image not found", deleted);
        } catch (Exception e) {
            return ApiResponse.error("Failed to delete image: " + e.getMessage());
        }
    }

    /**
     * 只清空当前登录用户的采集历史，不影响其他用户。
     */
    @DeleteMapping("/images")
    public ApiResponse<Integer> clearImages() {
        try {
            int deleted = jniService.clearImages(StpUtil.getLoginIdAsLong());
            return ApiResponse.success("Image history cleared successfully", deleted);
        } catch (Exception e) {
            return ApiResponse.error("Failed to clear images: " + e.getMessage());
        }
    }

    /** 对已保存的多张RAW图像执行多帧坏点、行列检测，并可选择写入处理结果。 */
    @PostMapping("/images/multi-frame/analyze")
    public ApiResponse<MultiFrameAnalysisResponse> analyzeMultiFrame(
            @RequestBody MultiFrameAnalysisRequest request) {
        try {
            return ApiResponse.success(
                    "Multi-frame image analysis completed",
                    jniService.analyzeMultiFrame(StpUtil.getLoginIdAsLong(), request));
        } catch (Exception e) {
            return ApiResponse.error("Failed to analyze multi-frame images: " + e.getMessage());
        }
    }

    /** 生成暗场/平场模拟校准数据。 */
    @PostMapping("/calibrations/{calibrationType}/simulate")
    public ApiResponse<CalibrationSessionResponse> simulateCalibration(
            @PathVariable String calibrationType,
            @RequestBody(required = false) CalibrationRequest request) {
        try {
            return ApiResponse.success(
                    "Calibration simulation generated",
                    jniService.generateCalibration(StpUtil.getLoginIdAsLong(), calibrationType, request));
        } catch (Exception e) {
            return ApiResponse.error("Failed to generate calibration simulation: " + e.getMessage());
        }
    }

    /** 使用已保存的多帧图像构建暗场/平场校准数据。 */
    @PostMapping("/calibrations/{calibrationType}/from-images")
    public ApiResponse<CalibrationSessionResponse> buildCalibrationFromImages(
            @PathVariable String calibrationType,
            @RequestBody CalibrationRequest request) {
        try {
            return ApiResponse.success(
                    "Calibration session saved",
                    jniService.buildCalibrationFromImages(
                            StpUtil.getLoginIdAsLong(), calibrationType, request));
        } catch (Exception e) {
            return ApiResponse.error("Failed to build calibration session: " + e.getMessage());
        }
    }

    @GetMapping("/calibrations")
    public ApiResponse<List<CalibrationSessionResponse>> listCalibrations(
            @RequestParam(required = false) String type) {
        try {
            return ApiResponse.success(
                    "Calibration sessions fetched",
                    jniService.listCalibrations(StpUtil.getLoginIdAsLong(), type));
        } catch (Exception e) {
            return ApiResponse.error("Failed to list calibration sessions: " + e.getMessage());
        }
    }

    /** 获取当前登录用户的暗场/平场全局启用状态。 */
    @GetMapping("/calibrations/settings")
    public ApiResponse<CalibrationGlobalSettingsResponse> getCalibrationGlobalSettings() {
        try {
            return ApiResponse.success(
                    "Calibration global settings fetched",
                    jniService.getCalibrationGlobalSettings(StpUtil.getLoginIdAsLong()));
        } catch (Exception e) {
            return ApiResponse.error("Failed to fetch calibration global settings: " + e.getMessage());
        }
    }

    /** 保存当前登录用户的暗场/平场全局开关。 */
    @PutMapping("/calibrations/settings")
    public ApiResponse<CalibrationGlobalSettingsResponse> updateCalibrationGlobalSettings(
            @RequestBody CalibrationGlobalSettingsRequest request) {
        try {
            return ApiResponse.success(
                    "Calibration global settings updated",
                    jniService.updateCalibrationGlobalSettings(StpUtil.getLoginIdAsLong(), request));
        } catch (Exception e) {
            return ApiResponse.error("Failed to update calibration global settings: " + e.getMessage());
        }
    }

    @GetMapping("/calibrations/{sessionId}")
    public ApiResponse<CalibrationSessionResponse> getCalibration(@PathVariable long sessionId) {
        try {
            return ApiResponse.success(
                    "Calibration session fetched",
                    jniService.getCalibration(StpUtil.getLoginIdAsLong(), sessionId));
        } catch (Exception e) {
            return ApiResponse.error("Failed to fetch calibration session: " + e.getMessage());
        }
    }

    @DeleteMapping("/calibrations/{sessionId}")
    public ApiResponse<Boolean> deleteCalibration(@PathVariable long sessionId) {
        try {
            return ApiResponse.success(
                    "Calibration session deleted",
                    jniService.deleteCalibration(StpUtil.getLoginIdAsLong(), sessionId));
        } catch (Exception e) {
            return ApiResponse.error("Failed to delete calibration session: " + e.getMessage());
        }
    }

    @GetMapping("/calibrations/{sessionId}/previews")
    public ApiResponse<List<CalibrationPreviewResponse>> listCalibrationPreviews(
            @PathVariable long sessionId,
            @RequestParam(defaultValue = "0") int limit) {
        try {
            return ApiResponse.success(
                    "Calibration previews fetched",
                    jniService.listCalibrationPreviews(
                            StpUtil.getLoginIdAsLong(), sessionId, limit));
        } catch (Exception e) {
            return ApiResponse.error("Failed to fetch calibration previews: " + e.getMessage());
        }
    }

    @GetMapping("/calibrations/{sessionId}/reference-preview")
    public ApiResponse<CalibrationPreviewResponse> getCalibrationReferencePreview(@PathVariable long sessionId) {
        try {
            return ApiResponse.success(
                    "Calibration reference preview fetched",
                    jniService.getCalibrationReferencePreview(StpUtil.getLoginIdAsLong(), sessionId));
        } catch (Exception e) {
            return ApiResponse.error("Failed to fetch calibration reference preview: " + e.getMessage());
        }
    }

    @GetMapping("/calibrations/{sessionId}/reference-previews")
    public ApiResponse<List<CalibrationPreviewResponse>> listCalibrationReferencePreviews(@PathVariable long sessionId) {
        try {
            return ApiResponse.success(
                    "Calibration reference previews fetched",
                    jniService.listCalibrationReferencePreviews(StpUtil.getLoginIdAsLong(), sessionId));
        } catch (Exception e) {
            return ApiResponse.error("Failed to fetch calibration reference previews: " + e.getMessage());
        }
    }

    @PostMapping("/commands/query-status")
    public ApiResponse<String> sendQueryStatus() {
        try {
            jniService.sendQueryStatus();
            return ApiResponse.success("Query-status command sent successfully");
        } catch (Exception e) {
            return ApiResponse.error("Failed to send query-status command: " + e.getMessage());
        }
    }

    @PostMapping("/commands/full-config")
    public ApiResponse<String> sendFullConfig(@RequestBody BridgeFullConfigRequest request) {
        try {
            jniService.sendFullConfig(request == null ? null : request.getConfigBytes());
            return ApiResponse.success("Full-config command sent successfully");
        } catch (Exception e) {
            return ApiResponse.error("Failed to send full-config command: " + e.getMessage());
        }
    }
}
