package springbootjni.dto.jni;

import lombok.Data;

/**
 * 用户级校准包设置。
 *
 * <p>暗场、平场会话组成一个可追溯的校准包；缺陷地图是否参与后续单帧处理由
 * {@link #defectMapEnabled} 独立决定。两者均不会修改原始 RAW。</p>
 */
@Data
public class CalibrationGlobalSettingsRequest {
    private Boolean enabled;
    private Long darkCalibrationId;
    private Long flatCalibrationId;
    private Boolean defectMapEnabled;

    /** HDR模式是否启用独立的HG/LG暗场、平场校准包。 */
    private Boolean hdrEnabled;
    private Long hdrDarkCalibrationId;
    private Long hdrFlatCalibrationId;
    private Boolean hdrDefectMapEnabled;
}
