package springbootjni.controller;

import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import springbootjni.dto.ApiResponse;
import springbootjni.dto.jni.BridgeConnectRequest;
import springbootjni.dto.jni.BridgeFullConfigRequest;
import springbootjni.dto.jni.BridgeStateResponse;
import springbootjni.dto.jni.ImageFrameResponse;
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
            TriggerCaptureResponse response = jniService.sendTriggerOnce(userId, autoProcess);
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
