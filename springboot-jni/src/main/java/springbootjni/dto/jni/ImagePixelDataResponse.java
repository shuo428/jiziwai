package springbootjni.dto.jni;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 按需返回一小块 RAW16 像素窗口。
 *
 * <p>完整 RAW 图像仍保存在文件系统中，前端只在用户点击查看时按 ROI 请求，
 * 避免一次把整幅 800x600 或更大图像的像素矩阵塞进浏览器。</p>
 */
@Data
public class ImagePixelDataResponse {
    private Long imageId;
    private String sourceMode;
    /** 给前端直接展示的来源名称，避免把“已重排原图”误解成 FPGA 原始 payload。 */
    private String sourceLabel;
    /** RAW16 文件的空间语义：这里返回的是已经转成正常行列坐标的二维图像。 */
    private String spatialOrder;
    /** 当前帧 FPGA payload 原始读出顺序；raw16le.bin 已按该顺序重排。 */
    private String readoutOrder;
    /** 对 sourceMode / spatialOrder / readoutOrder 的人读说明。 */
    private String sourceDescription;
    private Integer width;
    private Integer height;
    @JsonProperty("xStart")
    private Integer xStart;
    @JsonProperty("yStart")
    private Integer yStart;
    @JsonProperty("xEnd")
    private Integer xEnd;
    @JsonProperty("yEnd")
    private Integer yEnd;
    private Integer roiWidth;
    private Integer roiHeight;
    private Integer storageBitDepth;
    private Integer effectiveBitDepth;
    private String pixelFormat;
    private String displayFormat;
    private String rawFileByteOrder;
    private Boolean fullFrame;
    private Integer pixelMin;
    private Integer pixelMax;
    private Double pixelMean;
    private List<List<Integer>> rows;
    private List<String> hexRows;
}
