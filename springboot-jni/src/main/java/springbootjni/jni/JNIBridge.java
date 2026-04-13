package springbootjni.jni;

import springbootjni.service.JNICallbackService;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

public class JNIBridge {

    private JNICallbackService jniCallbackService;

    public void setJniCallbackService(JNICallbackService jniCallbackService) {
        this.jniCallbackService = jniCallbackService;
    }

    static {
        try {
            String dllName = "spectra.dll";
            InputStream in = JNIBridge.class.getResourceAsStream("/" + dllName);

            if (in == null) {
                throw new RuntimeException("DLL not found in resources: " + dllName);
            }

            File temp = File.createTempFile("spectra", ".dll");
            temp.deleteOnExit();

            Files.copy(in, temp.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            System.load(temp.getAbsolutePath());

        } catch (IOException e) {
            throw new RuntimeException("Failed to load DLL", e);
        }
    }

    // ==========================================
    // 原有接口：持续监听模式
    // ==========================================

    // 启动 C++ 接收线程
    public native void startListening();

    // 关闭 C++ 接收线程
    public native void stopListening();

    // ==========================================
    // 按次数采集图像
    // ==========================================

    /**
     * 按指定次数采集光谱图像
     * 每张图像会经过CUDA处理（增强+去噪）后通过回调返回
     * 
     * @param count 需要采集的图像数量
     * @return 0表示启动成功，负数表示错误
     */
    public native int captureImages(int count);
    
    /**
     * 对已有图像重新处理
     * 当处理效果不满意时可以调用此方法重新处理
     * 
     * @param imageData 原始或已处理的图像数据
     * @param processType 处理类型：0=默认(增强+去噪), 1=仅增强, 2=仅去噪
     * @return 处理后的图像数据
     */
    public native byte[] reprocessImage(byte[] imageData, int processType);
    
    /**
     * 带自定义参数的图像处理
     * 
     * @param imageData 图像数据
     * @param brightness 亮度调整 (-1.0 to 1.0)
     * @param contrast 对比度调整 (0.0 to 2.0)
     * @param denoiseStrength 去噪强度 (1-10)
     * @param applyEnhance 是否应用增强
     * @param applyDenoise 是否应用去噪
     * @return 处理后的图像数据
     */
    public native byte[] processImageWithParams(
        byte[] imageData,
        float brightness,
        float contrast,
        int denoiseStrength,
        boolean applyEnhance,
        boolean applyDenoise
    );
    
    // ==========================================
    // 回调方法 (需要子类实现或在此类中实现)
    // ==========================================
    
    /**
     * 持续监听模式下的数据回调
     * @param data 接收到的处理后的图像数据
     */
    public void onDataReceived(byte[] data) {
        if (jniCallbackService != null) {
            jniCallbackService.onDataReceived(data);
        }
    }
    
    /**
     * 按次数采集模式下的图像回调
     * @param index 图像索引 (从0开始)
     * @param data 处理后的图像数据
     */
    public void onImageCaptured(int index, byte[] data) {
        if (jniCallbackService != null) {
            jniCallbackService.onImageCaptured(index, data);
        }
    }

}
