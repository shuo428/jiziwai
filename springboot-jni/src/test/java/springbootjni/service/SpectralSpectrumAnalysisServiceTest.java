package springbootjni.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import springbootjni.dto.jni.SpectrumAnalysisRequest;
import springbootjni.dto.jni.SpectrumAnalysisResponse;
import springbootjni.dto.jni.SpectrumExtractionResponse;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 用已知峰形验证只分析正向峰，并防止亚像素峰中心更新再次丢失。无需数据库或硬件。 */
class SpectralSpectrumAnalysisServiceTest {
    private SpectralSpectrumExtractionService extraction;
    private SpectralSpectrumAnalysisService service;

    @BeforeEach
    void setUp() {
        extraction = mock(SpectralSpectrumExtractionService.class);
        SpectralSpectrumPreprocessingService preprocessing = mock(SpectralSpectrumPreprocessingService.class);
        // 仅替代保存动作，检测、拟合和指标计算全部运行真实服务代码。
        JdbcTemplate jdbc = mock(JdbcTemplate.class, call -> {
            if (!"queryForObject".equals(call.getMethod().getName())) {
                return RETURNS_DEFAULTS.answer(call);
            }
            RowMapper<?> mapper = call.getArgument(1);
            ResultSet row = mock(ResultSet.class);
            when(row.getLong("id")).thenReturn(1L);
            when(row.getObject("created_at", OffsetDateTime.class)).thenReturn(OffsetDateTime.now());
            return mapper.mapRow(row, 0);
        });
        service = new SpectralSpectrumAnalysisService(jdbc, new ObjectMapper(), extraction, preprocessing);
    }

    @Test
    void mixedCurveKeepsOnlyPositivePeakAndFitsSubpixelCenter() {
        List<SpectrumExtractionResponse.Point> points = new ArrayList<>();
        for (int x = 0; x < 128; x++) {
            points.add(new SpectrumExtractionResponse.Point(x,
                    100.0 + gaussian(x, 35.3, 3.0, 100.0) - gaussian(x, 90.2, 4.0, 70.0)));
        }
        SpectrumAnalysisResponse result = analyze(points);
        assertEquals(1, result.getPeakCount().intValue());
        SpectrumAnalysisResponse.Peak peak = result.getPeaks().get(0);
        assertEquals("POSITIVE", peak.getPolarity());
        assertEquals(35.3, peak.getFittedCenterPixel(), 0.02);
        assertEquals(3.0 * 2.0 * Math.sqrt(2.0 * Math.log(2.0)), peak.getFwhmPixels(), 0.05);
        assertEquals(100.0 * 3.0 * Math.sqrt(2.0 * Math.PI), peak.getArea(), 1.0);
        assertEquals("GOOD", peak.getFitQuality());
    }

    @Test
    void isolatedNegativeDipDoesNotBecomeAPositivePeak() {
        List<SpectrumExtractionResponse.Point> points = new ArrayList<>();
        for (int x = 0; x < 128; x++) {
            points.add(new SpectrumExtractionResponse.Point(x, 100.0 - gaussian(x, 60.2, 4.0, 70.0)));
        }
        SpectrumAnalysisResponse result = analyze(points);
        assertEquals(0, result.getCandidatePeakCount().intValue());
        assertTrue(result.getPeaks().isEmpty());
    }

    private SpectrumAnalysisResponse analyze(List<SpectrumExtractionResponse.Point> points) {
        SpectrumExtractionResponse spectrum = new SpectrumExtractionResponse();
        spectrum.setId(1L);
        spectrum.setImageId(2L);
        spectrum.setCaptureId(3L);
        spectrum.setPoints(points);
        when(extraction.getLatest(4L, 2L)).thenReturn(spectrum);
        SpectrumAnalysisRequest request = new SpectrumAnalysisRequest();
        request.setSource("EXTRACTED");
        return service.analyze(4L, 2L, request);
    }

    private static double gaussian(double x, double center, double sigma, double amplitude) {
        double offset = (x - center) / sigma;
        return amplitude * Math.exp(-0.5 * offset * offset);
    }
}
