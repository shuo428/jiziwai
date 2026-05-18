package springbootjni.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import springbootjni.dto.ApiResponse;
import springbootjni.dto.jni.BridgeConnectRequest;
import springbootjni.dto.jni.BridgeFullConfigRequest;
import springbootjni.dto.jni.BridgeStateResponse;
import springbootjni.service.JNIService;

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
    public ApiResponse<String> sendTriggerOnce() {
        try {
            jniService.sendTriggerOnce();
            return ApiResponse.success("Trigger-once command sent successfully");
        } catch (Exception e) {
            return ApiResponse.error("Failed to send trigger-once command: " + e.getMessage());
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
