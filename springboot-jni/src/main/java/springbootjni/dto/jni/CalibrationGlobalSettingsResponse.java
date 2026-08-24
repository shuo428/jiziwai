package springbootjni.dto.jni;

import lombok.Data;

import java.time.OffsetDateTime;

/** 当前登录用户所选校准包及其缺陷地图使用状态。 */
@Data
public class CalibrationGlobalSettingsResponse {
    /** 是否在正式采集时使用所选校准包的暗场/平场参考。 */
    private boolean enabled;

    /** 当前校准包选中的暗场会话。 */
    private Long darkCalibrationId;

    /** 当前校准包选中的平场会话。 */
    private Long flatCalibrationId;

    /** 是否在校准后应用该校准包由多帧生成的稳定缺陷地图。 */
    private boolean defectMapEnabled;

    /** 所选暗场、平场会话是否能组合为同尺寸的有效校准包。 */
    private boolean calibrationPackageReady;

    /** 当前校准包是否含有可用的多帧缺陷地图。 */
    private boolean defectMapAvailable;

    /** HDR模式是否启用独立HG/LG暗场、平场校准包。 */
    private boolean hdrEnabled;

    /** 当前HDR校准包选中的HDR暗场会话。 */
    private Long hdrDarkCalibrationId;

    /** 当前HDR校准包选中的HDR平场会话。 */
    private Long hdrFlatCalibrationId;

    /** HDR模式是否在HG/LG分别校准后应用对应平面的稳定缺陷地图。 */
    private boolean hdrDefectMapEnabled;

    /** 所选HDR暗场、HDR平场会话是否能组合为同尺寸的有效HDR校准包。 */
    private boolean hdrCalibrationPackageReady;

    /** 当前HDR校准包是否含有可用的HG/LG多帧缺陷地图。 */
    private boolean hdrDefectMapAvailable;

    private Integer width;
    private Integer height;

    /** 当前是否至少保存过一组 READY 暗场会话，仅作配置页提示。 */
    private boolean darkReferenceAvailable;

    /** 当前是否至少保存过一组 READY 平场会话，仅作配置页提示。 */
    private boolean flatReferenceAvailable;

    /** 当前是否至少保存过一组 READY HDR暗场会话，仅作配置页提示。 */
    private boolean hdrDarkReferenceAvailable;

    /** 当前是否至少保存过一组 READY HDR平场会话，仅作配置页提示。 */
    private boolean hdrFlatReferenceAvailable;

    /** 最近修改全局开关的时间。 */
    private OffsetDateTime updatedAt;

    /** 给界面直接展示的状态说明。 */
    private String message;
}
