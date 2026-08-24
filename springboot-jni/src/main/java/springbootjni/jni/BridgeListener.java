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
     * @param fpgaPayload FPGA 图像通道直接收到的原始有效像素 payload。
     *                    它保持 FPGA/芯片读出顺序，不经过 GLUX1605BSI lane 重排，
     *                    仅用于追溯、回放和验证重排算法。
     */
    void onImageFrame(int width, int height, short[] pixels16, byte[] pixels8, byte[] fpgaPayload);

    /**
     * 收到 HDR 双增益图像后的回调。
     *
     * <p>当前 FPGA/上位机约定一次触发返回一个双平面 payload：
     * 前半段为完整 HG 平面，后半段为完整 LG 平面。native 已经分别完成
     * GLUX1605BSI HDR 4-lane 有效像素重排，因此 hgPixels16 和 lgPixels16
     * 都是正常行列顺序的 RAW16 低12位像素数组。</p>
     *
     * @param width 图像宽度
     * @param height 图像高度
     * @param hgPixels16 高增益平面，长度应为 width * height
     * @param lgPixels16 低增益平面，长度应为 width * height
     * @param fpgaPayload FPGA 图像通道直接收到的原始双平面 payload，顺序保持 HG + LG
     */
    void onHdrImageFrame(int width, int height, short[] hgPixels16, short[] lgPixels16, byte[] fpgaPayload);

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
