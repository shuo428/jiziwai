package springbootjni.dto.jni;

import lombok.Data;

import java.util.List;

/**
 * 按需查看 FPGA 直接输出的原始有效像素 payload。
 *
 * <p>它和 ImagePixelDataResponse 不同：ImagePixelDataResponse 读取的是已经重排成
 * 正常二维坐标的 raw16le.bin；本响应读取的是 fpga_payload.bin，保持 FPGA/芯片输出顺序。</p>
 */
@Data
public class FpgaPayloadPixelDataResponse {
    private Long imageId;
    private Integer width;
    private Integer height;
    private String pixelFormat;
    private String readoutOrder;
    private String payloadStorageUri;
    private String payloadSha256;
    private String rawFileByteOrder;
    private Integer storageBitDepth;
    private Integer effectiveBitDepth;
    private Integer laneCount;
    private Integer laneWidth;
    /** payload中包含几个完整图像平面；普通模式为1，HDR模式为2(HG+LG)。 */
    private Integer payloadPlaneCount;
    private Integer payloadPixelCount;
    private Integer payloadStart;
    private Integer payloadEnd;
    private Integer returnedCount;
    private Integer maxWindowCount;
    /** true 表示 hexRows 返回的是完整 payload 矩阵，pixels 明细表会为空以避免浏览器卡顿。 */
    private Boolean fullFrame;
    /** 当前像素矩阵显示格式，第一版固定为 HEX_WORD，即高字节在前的 16 位字值显示。 */
    private String displayFormat;
    private Integer pixelMin;
    private Integer pixelMax;
    private Double pixelMean;
    private String sourceDescription;
    /** 按 FPGA payload 行顺序返回的两字节像素矩阵；完整预览时使用。 */
    private List<String> hexRows;
    private List<PixelRecord> pixels;

    @Data
    public static class PixelRecord {
        /** FPGA payload 中的线性像素序号。 */
        private Integer payloadIndex;
        /** 当前像素所属平面：普通模式为SINGLE，HDR模式为HG或LG。 */
        private String plane;
        /** 当前平面内的线性像素序号。 */
        private Integer planePixelIndex;
        /** FPGA payload 当前像素所在的有效行；它仍对应传感器输出的第几行。 */
        private Integer payloadRow;
        /** 当前行内的 payload 顺序位置，不等于重排后图像 x 坐标。 */
        private Integer payloadColumn;
        /** GLUX1605BSI HDR 4-lane 输出中的 lane 编号；非 4-lane 顺序时为 0。 */
        private Integer lane;
        /** 当前 lane 内第几个 sample。 */
        private Integer sample;
        /** native 重排后落到正常图像中的 x 坐标。 */
        private Integer imageX;
        /** native 重排后落到正常图像中的 y 坐标。 */
        private Integer imageY;
        /** 人读的 16 位字节顺序，高字节在前，例如 00 A0。 */
        private String hexWord;
        /** fpga_payload.bin 文件中的真实小端字节顺序，例如 A0 00。 */
        private String hexFileBytes;
        /** 低 12 位有效 DN 值。 */
        private Integer dn;
        /** 原始 16 位容器值；当前协议要求高 4 位为 0。 */
        private Integer raw16Value;
    }
}
