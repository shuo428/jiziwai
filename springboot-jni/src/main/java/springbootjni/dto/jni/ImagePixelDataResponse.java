package springbootjni.dto.jni;

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
    private Integer width;
    private Integer height;
    private Integer xStart;
    private Integer yStart;
    private Integer xEnd;
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
