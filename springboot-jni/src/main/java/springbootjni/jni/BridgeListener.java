package springbootjni.jni;

/**
 * Native 侧通过 JNI 回调到 Java 的监听接口。
 */
public interface BridgeListener {

    /**
     * 收到一帧图像后的回调。
     *
     * @param width    图像宽度
     * @param height   图像高度
     * @param pixels16 16 位灰度数组，长度应为 width * height。
     *                 当前协议下每个元素只有低 12 位是有效像素值，范围 0~4095。
     * @param pixels8  8 位灰度数组，长度应为 width * height。
     *                 这是 native 侧把低 12 位有效值缩放到 0~255 后得到的显示图。
     */
    void onImageFrame(int width, int height, short[] pixels16, byte[] pixels8);

    /**
     * 收到状态包后的回调。
     *
     * @param statusBits FPGA 返回的状态位
     * @param errorCode  FPGA 返回的错误码
     */
    void onStatus(int statusBits, int errorCode);

    /**
     * 收到配置结果包后的回调。
     *
     * @param resultCode 结果码，0 通常表示成功
     * @param failedAddr 失败地址，仅失败时有业务意义
     */
    void onConfigAck(int resultCode, int failedAddr);

    /**
     * native 传输层发生错误时的统一回调。
     *
     * @param channel 出错通道，一般为 "control" 或 "image"
     * @param message 详细错误信息
     */
    void onTransportError(String channel, String message);
}
