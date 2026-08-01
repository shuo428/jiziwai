package springbootjni.dto.jni;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 一维像素域光谱提取结果。
 */
@Data
public class SpectrumExtractionResponse {
    private Long id;
    private Long imageId;
    private Long captureId;
    private String sourceMode;
    private String sourceQualityStatus;
    private String wavelengthAxis;
    private Roi roi;
    private Boolean rectified;
    private Integer maxShiftPixels;
    private Integer shiftMin;
    private Integer shiftMax;
    private Double shiftMeanAbs;
    private String integrationMethod;
    private Integer pointCount;
    private Double intensityMin;
    private Double intensityMax;
    private Double intensityMean;
    private List<Point> points;
    private String algorithmVersion;
    private String summaryMessage;
    private Map<String, Object> details;
    private OffsetDateTime createdAt;

    @Data
    public static class Roi {
        private Integer xStart;
        private Integer xEnd;
        private Integer yStart;
        private Integer yEnd;
    }

    @Data
    public static class Point {
        private Integer pixelIndex;
        private Double intensity;

        public Point() {
        }

        public Point(Integer pixelIndex, Double intensity) {
            this.pixelIndex = pixelIndex;
            this.intensity = intensity;
        }
    }
}
