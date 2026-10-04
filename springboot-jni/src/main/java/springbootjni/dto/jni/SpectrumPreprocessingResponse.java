package springbootjni.dto.jni;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 一维光谱预处理响应。
 */
@Data
public class SpectrumPreprocessingResponse {
    private Long id;
    private Long spectrumId;
    private Long imageId;
    private Long captureId;

    private Map<String, Object> preprocessingSteps;
    private Integer pointCount;

    private Double originalIntensityMin;
    private Double originalIntensityMax;
    private Double originalIntensityMean;
    private Double processedIntensityMin;
    private Double processedIntensityMax;
    private Double processedIntensityMean;
    private Double dynamicRangeBefore;
    private Double dynamicRangeAfter;
    private Double noiseBefore;
    private Double noiseAfter;
    private Integer spikeCount;

    /**
     * 原始一维光谱点列，来自 t_spectrum_extraction.spectrum_points。
     */
    private List<SpectrumExtractionResponse.Point> originalPoints;

    /**
     * 预处理后一维光谱点列。
     */
    private List<SpectrumExtractionResponse.Point> points;

    private String algorithmVersion;
    private String summaryMessage;
    private Map<String, Object> details;
    private OffsetDateTime createdAt;
}
