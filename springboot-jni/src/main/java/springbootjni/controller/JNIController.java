package springbootjni.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import springbootjni.dto.ApiResponse;
import springbootjni.jni.JNIBridge;
import springbootjni.service.JNICallbackService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/jni")
public class JNIController {

    private final JNICallbackService jniCallbackService;
    private JNIBridge bridge;

    @GetMapping("/start")
    public ApiResponse<String> startJNI() {
        try {
            new Thread(() -> {
                bridge = new JNIBridge();
                bridge.setJniCallbackService(jniCallbackService);
                bridge.startListening();
            }).start();
            System.out.println("JNI listener started");
            return ApiResponse.success("JNI bridge listener started successfully",
                    "Listener is now running in background");
        } catch (Exception e) {
            return ApiResponse.error("Failed to start JNI bridge: " + e.getMessage());
        }
    }

    @GetMapping("/stop")
    public ApiResponse<String> stopJNI() {
        try {
            if (bridge != null) {
                bridge.stopListening();
            }
            System.out.println("JNI listener stopped");
            return ApiResponse.success("JNI bridge listener stopped successfully");
        } catch (Exception e) {
            return ApiResponse.error("Failed to stop JNI bridge: " + e.getMessage());
        }
    }

    @PostMapping("/capture")
    public ApiResponse<String> captureImages(@RequestParam int count) {
        try {
            if (count <= 0 || count > 100) {
                return ApiResponse.error("Invalid count. Must be between 1 and 100");
            }
            
            new Thread(() -> {
                JNIBridge captureBridge = new JNIBridge();
                captureBridge.setJniCallbackService(jniCallbackService);
                int result = captureBridge.captureImages(count);
                if (result != 0) {
                    System.err.println("Failed to capture images, error code: " + result);
                }
            }).start();
            
            System.out.println("Started capturing " + count + " images");
            return ApiResponse.success("Image capture started successfully",
                    "Capturing " + count + " spectral images");
        } catch (Exception e) {
            return ApiResponse.error("Failed to start image capture: " + e.getMessage());
        }
    }
}
