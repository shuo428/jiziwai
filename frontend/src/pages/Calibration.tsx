import React, { useEffect, useRef, useState } from "react";
import { Button, Card, Divider, InputNumber, Modal, Segmented, Select, Space, Table, Tag, Typography } from "antd";
import { Beaker, CircleStop, Eye, RefreshCw, Save, Settings2, Trash2 } from "lucide-react";
import { toast } from "sonner";

import { jniBridgeService } from "../service/jniBridgeService";
import { useJNIStore } from "../store/jniStore";
import type {
    CalibrationPreviewRecord,
    CalibrationGlobalSettingsRecord,
    CalibrationSessionRecord,
} from "../types/jni";

const { Title, Text } = Typography;

type CalibrationType = "DARK" | "FLAT" | "HDR_DARK" | "HDR_FLAT";
type CalibrationMode = "NORMAL" | "HDR";
type HdrCalibrationType = "HDR_DARK" | "HDR_FLAT";
type HdrPlaneView = "HG" | "LG";

type CalibrationPageProps = {
    mode?: CalibrationMode;
};

const calibrationTypeOptions = [
    { value: "DARK", label: "暗场 DARK" },
    { value: "FLAT", label: "平场 FLAT" },
    { value: "HDR_DARK", label: "HDR暗场 HDR_DARK" },
    { value: "HDR_FLAT", label: "HDR平场 HDR_FLAT" },
] as const;

const isDarkCalibrationType = (type: CalibrationType): boolean => type === "DARK" || type === "HDR_DARK";
const isHdrCalibrationType = (type: CalibrationType): boolean => type === "HDR_DARK" || type === "HDR_FLAT";
const calibrationLabel = (type: CalibrationType): string => {
    if (type === "DARK") return "暗场";
    if (type === "FLAT") return "平场";
    if (type === "HDR_DARK") return "HDR暗场";
    return "HDR平场";
};
const calibrationPrefix = (type: CalibrationType): string => {
    if (type === "DARK") return "D";
    if (type === "FLAT") return "F";
    if (type === "HDR_DARK") return "HD";
    return "HF";
};

const statusColor = (status?: string): string => {
    if (status === "READY") return "green";
    if (status === "PROCESSING") return "blue";
    if (status === "FAILED") return "red";
    return "default";
};

const sleep = (milliseconds: number): Promise<void> =>
    new Promise((resolve) => window.setTimeout(resolve, milliseconds));

const clampPreviewZoom = (value: number): number => Math.max(0.5, Math.min(6, value));

const calibrationQualityColor = (status?: string | null): string => {
    if (status === "PASS") return "green";
    if (status === "WARNING") return "orange";
    if (status === "FAIL") return "red";
    return "default";
};

const objectFromRecord = (
    record: Record<string, unknown> | null | undefined,
    key: string,
): Record<string, unknown> | null => {
    const value = record?.[key];
    return value && typeof value === "object" && !Array.isArray(value) ? (value as Record<string, unknown>) : null;
};

const stringFromRecord = (record: Record<string, unknown> | null | undefined, key: string): string | null => {
    const value = record?.[key];
    return typeof value === "string" ? value : null;
};

const numberFromRecord = (record: Record<string, unknown> | null | undefined, key: string): number | null => {
    const value = record?.[key];
    return typeof value === "number" ? value : null;
};

const stringArrayFromRecord = (record: Record<string, unknown> | null | undefined, key: string): string[] => {
    const value = record?.[key];
    return Array.isArray(value) ? value.map((item) => String(item)) : [];
};

const calibrationQualityOf = (
    session: CalibrationSessionRecord | null | undefined,
): Record<string, unknown> | null => objectFromRecord(session?.summary, "calibrationQuality");

const calibrationQualityStatusOf = (session: CalibrationSessionRecord | null | undefined): string | null =>
    stringFromRecord(calibrationQualityOf(session), "qualityStatus");

const formatMetricNumber = (
    metrics: Record<string, unknown> | null | undefined,
    key: string,
    digits = 2,
    suffix = "",
): string => {
    const value = numberFromRecord(metrics, key);
    return value === null ? "-" : `${value.toFixed(digits)}${suffix}`;
};

const formatMetricRatio = (
    metrics: Record<string, unknown> | null | undefined,
    key: string,
    digits = 3,
): string => {
    const value = numberFromRecord(metrics, key);
    return value === null ? "-" : `${(value * 100).toFixed(digits)}%`;
};

const CalibrationPage: React.FC<CalibrationPageProps> = ({ mode = "NORMAL" }) => {
    const { bridgeState, connectionForm } = useJNIStore();
    const isHdrMode = mode === "HDR";
    const [calibrationType, setCalibrationType] = useState<CalibrationType>(isHdrMode ? "HDR_DARK" : "DARK");
    const [frameCount, setFrameCount] = useState(8);
    const [intervalMs, setIntervalMs] = useState(1000);
    const [activeDarkCalibrationId, setActiveDarkCalibrationId] = useState<number | null>(null);
    const [activeFlatCalibrationId, setActiveFlatCalibrationId] = useState<number | null>(null);
    const [activeHdrDarkCalibrationId, setActiveHdrDarkCalibrationId] = useState<number | null>(null);
    const [activeHdrFlatCalibrationId, setActiveHdrFlatCalibrationId] = useState<number | null>(null);
    const [loadingAction, setLoadingAction] = useState<string | null>(null);
    const [collectedIds, setCollectedIds] = useState<number[]>([]);
    const [sessions, setSessions] = useState<CalibrationSessionRecord[]>([]);
    const [darkPreviewSession, setDarkPreviewSession] = useState<CalibrationSessionRecord | null>(null);
    const [darkReferencePreviews, setDarkReferencePreviews] = useState<CalibrationPreviewRecord[]>([]);
    const [darkPreviews, setDarkPreviews] = useState<CalibrationPreviewRecord[]>([]);
    const [flatPreviewSession, setFlatPreviewSession] = useState<CalibrationSessionRecord | null>(null);
    const [flatReferencePreviews, setFlatReferencePreviews] = useState<CalibrationPreviewRecord[]>([]);
    const [flatPreviews, setFlatPreviews] = useState<CalibrationPreviewRecord[]>([]);
    const [hdrDarkPreviewSession, setHdrDarkPreviewSession] = useState<CalibrationSessionRecord | null>(null);
    const [hdrDarkReferencePreviews, setHdrDarkReferencePreviews] = useState<CalibrationPreviewRecord[]>([]);
    const [hdrDarkPreviews, setHdrDarkPreviews] = useState<CalibrationPreviewRecord[]>([]);
    const [hdrFlatPreviewSession, setHdrFlatPreviewSession] = useState<CalibrationSessionRecord | null>(null);
    const [hdrFlatReferencePreviews, setHdrFlatReferencePreviews] = useState<CalibrationPreviewRecord[]>([]);
    const [hdrFlatPreviews, setHdrFlatPreviews] = useState<CalibrationPreviewRecord[]>([]);
    const [globalSettings, setGlobalSettings] = useState<CalibrationGlobalSettingsRecord | null>(null);
    const [zoomPreview, setZoomPreview] = useState<{
        type: CalibrationType;
        session: CalibrationSessionRecord | null;
        preview: CalibrationPreviewRecord;
    } | null>(null);
    const [zoomScale, setZoomScale] = useState(1);
    const [hdrPlaneViewByType, setHdrPlaneViewByType] = useState<Record<HdrCalibrationType, HdrPlaneView>>({
        HDR_DARK: "HG",
        HDR_FLAT: "HG",
    });
    const stopRef = useRef(false);
    const activeCalibrationOptions = calibrationTypeOptions.filter((option) =>
        isHdrMode ? isHdrCalibrationType(option.value) : !isHdrCalibrationType(option.value),
    );

    const hdrPlaneViewOf = (type: CalibrationType): HdrPlaneView =>
        isHdrCalibrationType(type) ? hdrPlaneViewByType[type as HdrCalibrationType] : "HG";

    const setHdrPlaneView = (type: CalibrationType, plane: HdrPlaneView): void => {
        if (!isHdrCalibrationType(type)) {
            return;
        }
        setHdrPlaneViewByType((current) => ({
            ...current,
            [type as HdrCalibrationType]: plane,
        }));
    };

    const matchesCurrentImageSpec = (session: CalibrationSessionRecord): boolean =>
        session.width === connectionForm.expectedWidth && session.height === connectionForm.expectedHeight;

    const loadSessions = async (): Promise<void> => {
        const records = (await jniBridgeService.listCalibrations()) ?? [];
        setSessions(records);
        const latestDark = records.find((item) => item.calibrationType === "DARK");
        const latestFlat = records.find((item) => item.calibrationType === "FLAT");
        const latestHdrDark = records.find((item) => item.calibrationType === "HDR_DARK");
        const latestHdrFlat = records.find((item) => item.calibrationType === "HDR_FLAT");
        const previewTargets = isHdrMode ? [latestHdrDark, latestHdrFlat] : [latestDark, latestFlat];
        await Promise.all(previewTargets.map((target) => (target ? showCalibrationPreview(target) : Promise.resolve())));
    };

    const showCalibrationPreview = async (session: CalibrationSessionRecord): Promise<void> => {
        const type = session.calibrationType as CalibrationType;
        const resetReference = () => {
            if (type === "DARK") setDarkReferencePreviews([]);
            if (type === "FLAT") setFlatReferencePreviews([]);
            if (type === "HDR_DARK") setHdrDarkReferencePreviews([]);
            if (type === "HDR_FLAT") setHdrFlatReferencePreviews([]);
        };
        if (type === "DARK") setDarkPreviewSession(session);
        if (type === "FLAT") setFlatPreviewSession(session);
        if (type === "HDR_DARK") setHdrDarkPreviewSession(session);
        if (type === "HDR_FLAT") setHdrFlatPreviewSession(session);
        resetReference();
        try {
            // limit=0 表示请求该校准包的全部原始样本，避免采集帧数超过默认值时预览被截断。
            const [previews, referencePreviews] = await Promise.all([
                jniBridgeService.listCalibrationPreviews(session.id, 0),
                jniBridgeService.listCalibrationReferencePreviews(session.id),
            ]);
            if (type === "DARK") {
                setDarkPreviews(previews);
                setDarkReferencePreviews(referencePreviews);
            }
            if (type === "FLAT") {
                setFlatPreviews(previews);
                setFlatReferencePreviews(referencePreviews);
            }
            if (type === "HDR_DARK") {
                setHdrDarkPreviews(previews);
                setHdrDarkReferencePreviews(referencePreviews);
            }
            if (type === "HDR_FLAT") {
                setHdrFlatPreviews(previews);
                setHdrFlatReferencePreviews(referencePreviews);
            }
        } catch (error) {
            if (type === "DARK") {
                setDarkPreviews([]);
                setDarkReferencePreviews([]);
            }
            if (type === "FLAT") {
                setFlatPreviews([]);
                setFlatReferencePreviews([]);
            }
            if (type === "HDR_DARK") {
                setHdrDarkPreviews([]);
                setHdrDarkReferencePreviews([]);
            }
            if (type === "HDR_FLAT") {
                setHdrFlatPreviews([]);
                setHdrFlatReferencePreviews([]);
            }
            toast.error(error instanceof Error ? error.message : "加载校准图片失败");
        }
    };

    const handleDeleteCalibration = (session: CalibrationSessionRecord): void => {
        const type = session.calibrationType as CalibrationType;
        const prefix = calibrationPrefix(type);
        const label = `${calibrationLabel(type)} ${prefix}-${String(session.sessionNumber).padStart(3, "0")}`;
        Modal.confirm({
            title: `确认删除${label}？`,
            content:
                "删除后该校准包不能再被选择或启用，参考图、缺陷地图和样本副本文件会被清理；已经采集的光谱图像不会删除，打开预览时会标注其历史校准包已删除。",
            okText: "确认删除",
            cancelText: "取消",
            okButtonProps: { danger: true },
            onOk: async () => {
                const loadingKey = `delete-${session.id}`;
                setLoadingAction(loadingKey);
                try {
                    await jniBridgeService.deleteCalibration(session.id);
                    setSessions((current) => current.filter((item) => item.id !== session.id));
                    if (type === "DARK") {
                        if (darkPreviewSession?.id === session.id) {
                            setDarkPreviewSession(null);
                            setDarkReferencePreviews([]);
                            setDarkPreviews([]);
                        }
                        if (activeDarkCalibrationId === session.id) {
                            setActiveDarkCalibrationId(null);
                        }
                    }
                    if (type === "FLAT") {
                        if (flatPreviewSession?.id === session.id) {
                            setFlatPreviewSession(null);
                            setFlatReferencePreviews([]);
                            setFlatPreviews([]);
                        }
                        if (activeFlatCalibrationId === session.id) {
                            setActiveFlatCalibrationId(null);
                        }
                    }
                    if (type === "HDR_DARK") {
                        if (hdrDarkPreviewSession?.id === session.id) {
                            setHdrDarkPreviewSession(null);
                            setHdrDarkReferencePreviews([]);
                            setHdrDarkPreviews([]);
                        }
                        if (activeHdrDarkCalibrationId === session.id) {
                            setActiveHdrDarkCalibrationId(null);
                        }
                    }
                    if (type === "HDR_FLAT") {
                        if (hdrFlatPreviewSession?.id === session.id) {
                            setHdrFlatPreviewSession(null);
                            setHdrFlatReferencePreviews([]);
                            setHdrFlatPreviews([]);
                        }
                        if (activeHdrFlatCalibrationId === session.id) {
                            setActiveHdrFlatCalibrationId(null);
                        }
                    }
                    await loadGlobalSettings();
                    toast.success(`${label} 已删除`);
                } catch (error) {
                    toast.error(error instanceof Error ? error.message : "删除校准包失败");
                } finally {
                    setLoadingAction(null);
                }
            },
        });
    };

    const loadGlobalSettings = async (): Promise<void> => {
        const settings = await jniBridgeService.getCalibrationGlobalSettings();
        setGlobalSettings(settings);
        setActiveDarkCalibrationId(settings.darkCalibrationId);
        setActiveFlatCalibrationId(settings.flatCalibrationId);
        setActiveHdrDarkCalibrationId(settings.hdrDarkCalibrationId);
        setActiveHdrFlatCalibrationId(settings.hdrFlatCalibrationId);
    };

    useEffect(() => {
        const nextDefaultType = isHdrMode ? "HDR_DARK" : "DARK";
        if (isHdrMode !== isHdrCalibrationType(calibrationType)) {
            setCalibrationType(nextDefaultType);
            setCollectedIds([]);
        }
    }, [calibrationType, isHdrMode]);

    useEffect(() => {
        loadSessions().catch(() => undefined);
        loadGlobalSettings().catch(() => undefined);
    }, [mode]);

    const collectFrames = async (): Promise<number[]> => {
        if (!bridgeState.connected) {
            throw new Error("请先在设备总览或配置管理中连接设备/模拟FPGA");
        }
        const count = Math.max(2, Math.min(64, Math.trunc(frameCount)));
        const ids: number[] = [];
        stopRef.current = false;
        for (let index = 0; index < count; index++) {
            if (stopRef.current) {
                break;
            }
            const frame = await jniBridgeService.triggerOnceAndWaitForFrame({
                autoProcess: false,
                captureScene: calibrationType,
            });
            ids.push(frame.id);
            setCollectedIds([...ids]);
            if (index < count - 1 && intervalMs > 0) {
                await sleep(Math.max(0, Math.trunc(intervalMs)));
            }
        }
        if (ids.length < 2) {
            throw new Error("有效采集帧少于2张，无法进行多帧分析");
        }
        return ids;
    };

    const handleSimulate = async (): Promise<void> => {
        setLoadingAction("simulate");
        try {
            const session = await jniBridgeService.simulateCalibration(calibrationType, {
                frameCount,
                width: connectionForm.expectedWidth,
                height: connectionForm.expectedHeight,
            });
            setSessions((current) => [session, ...current.filter((item) => item.id !== session.id)]);
            await showCalibrationPreview(session);
            toast.success(`${calibrationLabel(calibrationType)}模拟数据已保存`);
        } catch (error) {
            toast.error(error instanceof Error ? error.message : "生成模拟校准数据失败");
        } finally {
            setLoadingAction(null);
        }
    };

    const openCalibrationPreviewZoom = (
        type: CalibrationType,
        session: CalibrationSessionRecord | null,
        preview: CalibrationPreviewRecord,
    ): void => {
        setZoomPreview({ type, session, preview });
        setZoomScale(1);
    };

    const renderMetricCell = (label: string, value: string): React.ReactNode => (
        <div className="rounded-md bg-white/80 px-2 py-1.5">
            <Text className="block text-[11px] text-slate-400">{label}</Text>
            <span className="text-xs font-semibold text-slate-800">{value}</span>
        </div>
    );

    const renderCalibrationQualitySummary = (
        type: CalibrationType,
        session: CalibrationSessionRecord,
    ): React.ReactNode => {
        const quality = calibrationQualityOf(session);
        const status = stringFromRecord(quality, "qualityStatus");
        const summary = stringFromRecord(quality, "summaryMessage");
        const isDark = isDarkCalibrationType(type);
        const hgQuality = objectFromRecord(quality, "hgQuality");
        const lgQuality = objectFromRecord(quality, "lgQuality");
        const hdrPlane = hdrPlaneViewOf(type);
        const activePlaneQuality = isHdrCalibrationType(type)
            ? hdrPlane === "HG" ? hgQuality : lgQuality
            : quality;
        const metrics = objectFromRecord(activePlaneQuality, "metrics");
        const planeSummary = stringFromRecord(activePlaneQuality, "summaryMessage");
        const reasonsSource = stringArrayFromRecord(activePlaneQuality, "decisionReasons").length > 0
            ? activePlaneQuality
            : quality;
        const reasons = stringArrayFromRecord(reasonsSource, "decisionReasons").length > 0
            ? stringArrayFromRecord(reasonsSource, "decisionReasons")
            : stringArrayFromRecord(reasonsSource, "reasonMessages");
        return (
            <div className="rounded-lg border border-cyan-100 bg-cyan-50/70 p-3">
                <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
                    <Text className="text-xs font-medium text-cyan-900">
                        {isHdrCalibrationType(type) ? `${hdrPlane} 平面校准样本质量评价` : "校准样本质量评价"}
                    </Text>
                    <div className="flex flex-wrap gap-2">
                        {isHdrCalibrationType(type) && (
                            <Tag color="purple" className="m-0">
                                当前 {hdrPlane}
                            </Tag>
                        )}
                        <Tag color={calibrationQualityColor(status)} className="m-0">
                            {status ? `整包质量 ${status}` : "旧数据未评价"}
                        </Tag>
                    </div>
                </div>
                <Text className="block text-xs leading-5 text-slate-600">
                    {isHdrCalibrationType(type)
                        ? planeSummary || summary || "该历史包创建于校准样本质量评价模块之前，暂无HDR分平面质量结果。"
                        : summary || "该历史包创建于校准样本质量评价模块之前，暂无暗场/平场专用质量结果。"}
                </Text>
                {isHdrCalibrationType(type) && (
                    <div className="mt-2 flex flex-wrap gap-2">
                        <Tag color={calibrationQualityColor(stringFromRecord(hgQuality, "qualityStatus"))} className="m-0">
                            HG {stringFromRecord(hgQuality, "qualityStatus") ?? "未评价"}
                        </Tag>
                        <Tag color={calibrationQualityColor(stringFromRecord(lgQuality, "qualityStatus"))} className="m-0">
                            LG {stringFromRecord(lgQuality, "qualityStatus") ?? "未评价"}
                        </Tag>
                    </div>
                )}
                <div className="mt-2 grid grid-cols-2 gap-2">
                    {renderMetricCell("均值", formatMetricNumber(metrics, "meanDn", 2, " DN"))}
                    {renderMetricCell("鲁棒噪声", formatMetricNumber(metrics, "robustSigmaDn", 2, " DN"))}
                    {isDark
                        ? renderMetricCell("高亮异常", formatMetricRatio(metrics, "brightPixelRatio"))
                        : renderMetricCell("亮度比例", formatMetricRatio(metrics, "meanRatio", 2))}
                    {isDark
                        ? renderMetricCell("帧间波动", formatMetricNumber(metrics, "frameMeanStdDn", 2, " DN"))
                        : renderMetricCell("均匀性", formatMetricRatio(metrics, "uniformityRatio", 2))}
                    {!isDark && renderMetricCell("PRNU估计", formatMetricRatio(metrics, "prnuRatio", 2))}
                    {!isDark && renderMetricCell("帧间CV", formatMetricRatio(metrics, "temporalCv", 2))}
                </div>
                {reasons.length > 0 && (
                    <div className="mt-2 space-y-1">
                        {reasons.slice(0, 4).map((reason) => (
                            <div key={reason} className="text-xs leading-5 text-slate-500">
                                · {reason}
                            </div>
                        ))}
                    </div>
                )}
            </div>
        );
    };

    const renderDefectMapSummary = (session: CalibrationSessionRecord): React.ReactNode => {
        const summary = session.summary ?? {};
        const isHdr = isHdrCalibrationType(session.calibrationType as CalibrationType);
        const hdrPlane = hdrPlaneViewOf(session.calibrationType as CalibrationType);
        const planePrefix = hdrPlane === "HG" ? "hg" : "lg";
        const voteRatio = isHdr
            ? numberFromRecord(summary, `${planePrefix}VoteRatio`)
            : numberFromRecord(summary, "voteRatio");
        return (
            <div className="rounded-lg border border-slate-200 bg-white p-3">
                <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
                    <Text className="text-xs font-medium text-slate-700">稳定缺陷地图</Text>
                    <Tag color="blue" className="m-0">
                        投票阈值 {voteRatio === null ? "默认" : `${(voteRatio * 100).toFixed(0)}%`}
                    </Tag>
                </div>
                <Text className="block text-xs leading-5 text-slate-500">
                    这里只表示多帧中同一坐标稳定重复的坏点、异常行、异常列；它不是暗场/平场整体质量结论。
                </Text>
                <div className="mt-2 grid grid-cols-3 gap-2">
                    {renderMetricCell("稳定坏点", String(session.badPixelCount))}
                    {renderMetricCell("异常行", String(session.badRowCount))}
                    {renderMetricCell("异常列", String(session.badColumnCount))}
                </div>
                {isHdr && (
                    <div className="mt-2 grid grid-cols-3 gap-2">
                        {renderMetricCell(`${hdrPlane}坏点`, String(numberFromRecord(summary, `${planePrefix}BadPixelCount`) ?? 0))}
                        {renderMetricCell(`${hdrPlane}异常行`, String(numberFromRecord(summary, `${planePrefix}AbnormalRowCount`) ?? 0))}
                        {renderMetricCell(`${hdrPlane}异常列`, String(numberFromRecord(summary, `${planePrefix}AbnormalColumnCount`) ?? 0))}
                    </div>
                )}
            </div>
        );
    };

    const renderReferencePreviewCard = (
        type: CalibrationType,
        session: CalibrationSessionRecord,
        referencePreviews: CalibrationPreviewRecord[],
    ): React.ReactNode => {
        const isDark = isDarkCalibrationType(type);
        const isHdr = isHdrCalibrationType(type);
        const label = isHdr
            ? `${calibrationLabel(type)}最终HG/LG参考图`
            : isDark ? "最终暗场参考图" : "最终平场参考图";
        const note = isHdr
            ? "HDR校准不会先融合HG/LG；这里的HG参考图和LG参考图会在正式HDR采集时分别作用到对应平面，然后再进行HDR融合。"
            : isDark
            ? "这张图由多张暗场样本逐像素取中位数生成，后续普通图像扣暗场时实际读取它。"
            : "这张图由多张平场样本逐像素取中位数生成，后续普通图像平场增益校正时实际读取它。";
        return (
            <div className="rounded-lg border border-cyan-100 bg-cyan-50/60 p-3">
                <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
                    <div>
                        <Text className="block text-sm font-medium text-cyan-950">{label}</Text>
                        <Text className="text-xs leading-5 text-slate-600">{note}</Text>
                    </div>
                    <Tag color="cyan" className="m-0">
                        实际使用参考
                    </Tag>
                </div>
                {referencePreviews.length > 0 ? (
                    <div className={`grid gap-3 ${isHdr ? "md:grid-cols-2" : "grid-cols-1"}`}>
                        {referencePreviews.map((referencePreview) => (
                            <button
                                type="button"
                                key={`${referencePreview.previewType}-${referencePreview.storageUri}`}
                                className="group w-full overflow-hidden rounded-lg border border-cyan-100 bg-white text-left shadow-sm transition hover:border-cyan-300 hover:shadow-md"
                                title="点击放大查看最终参考图"
                                onClick={() => openCalibrationPreviewZoom(type, session, referencePreview)}
                            >
                                <div
                                    className="mx-auto flex w-full max-w-[720px] items-center justify-center overflow-hidden bg-slate-950 p-2"
                                    style={{
                                        aspectRatio: `${session.width || 4} / ${session.height || 3}`,
                                    }}
                                >
                                    <img
                                        src={referencePreview.imageDataUrl}
                                        alt={referencePreview.label || label}
                                        className="h-full w-full cursor-zoom-in object-contain"
                                    />
                                </div>
                                <div className="flex items-center justify-between border-t border-cyan-100 bg-white px-2 py-1 text-xs">
                                    <span className="text-slate-600">{referencePreview.label || label}</span>
                                    <Tag color="cyan" className="m-0 text-[11px]">
                                        {isHdr ? referencePreview.previewType : "REFERENCE"}
                                    </Tag>
                                </div>
                                <div className="border-t border-cyan-100 bg-cyan-50 px-2 pb-1 text-[11px] text-cyan-700 opacity-0 transition group-hover:opacity-100">
                                    点击放大
                                </div>
                            </button>
                        ))}
                    </div>
                ) : (
                    <div className="flex min-h-[180px] items-center justify-center rounded-lg border border-dashed border-cyan-200 bg-white/80 text-sm text-slate-400">
                        最终参考图暂不可用；请确认该校准包为 READY，且 reference raw16le 文件未被删除。
                    </div>
                )}
            </div>
        );
    };

    const handleCollectCalibration = async (): Promise<void> => {
        setLoadingAction("calibration");
        try {
            const ids = await collectFrames();
            const session = await jniBridgeService.buildCalibrationFromImages(calibrationType, { imageIds: ids });
            setSessions((current) => [session, ...current.filter((item) => item.id !== session.id)]);
            await showCalibrationPreview(session);
            toast.success(`已保存${calibrationLabel(calibrationType)}校准会话，共${ids.length}帧`);
        } catch (error) {
            toast.error(error instanceof Error ? error.message : "连续采集校准数据失败");
        } finally {
            setLoadingAction(null);
        }
    };

    const handleSaveCalibrationPackage = async (): Promise<void> => {
        const selectedDark = sessions.find((item) => item.id === activeDarkCalibrationId);
        const selectedFlat = sessions.find((item) => item.id === activeFlatCalibrationId);
        if (selectedDark && !matchesCurrentImageSpec(selectedDark)) {
            toast.error(`暗场尺寸 ${selectedDark.width}×${selectedDark.height} 与当前全局规格 ${connectionForm.expectedWidth}×${connectionForm.expectedHeight} 不一致`);
            return;
        }
        if (selectedFlat && !matchesCurrentImageSpec(selectedFlat)) {
            toast.error(`平场尺寸 ${selectedFlat.width}×${selectedFlat.height} 与当前全局规格 ${connectionForm.expectedWidth}×${connectionForm.expectedHeight} 不一致`);
            return;
        }

        setLoadingAction("global-settings");
        try {
            const settings = await jniBridgeService.updateCalibrationGlobalSettings({
                enabled: true,
                darkCalibrationId: activeDarkCalibrationId,
                flatCalibrationId: activeFlatCalibrationId,
                defectMapEnabled: true,
            });
            setGlobalSettings(settings);
            setActiveDarkCalibrationId(settings.darkCalibrationId);
            setActiveFlatCalibrationId(settings.flatCalibrationId);
            toast.success("当前校准包已保存并启用");
        } catch (error) {
            toast.error(error instanceof Error ? error.message : "保存校准包失败");
        } finally {
            setLoadingAction(null);
        }
    };

    const handleDisableCalibrationPackage = async (): Promise<void> => {
        setLoadingAction("global-settings-disable");
        try {
            const settings = await jniBridgeService.updateCalibrationGlobalSettings({
                enabled: false,
                darkCalibrationId: activeDarkCalibrationId,
                flatCalibrationId: activeFlatCalibrationId,
                defectMapEnabled: false,
            });
            setGlobalSettings(settings);
            setActiveDarkCalibrationId(settings.darkCalibrationId);
            setActiveFlatCalibrationId(settings.flatCalibrationId);
            toast.success("校准包已停用，后续采集将沿用原始 RAW 流程");
        } catch (error) {
            toast.error(error instanceof Error ? error.message : "停用校准包失败");
        } finally {
            setLoadingAction(null);
        }
    };

    const handleSaveHdrCalibrationPackage = async (): Promise<void> => {
        const selectedDark = sessions.find((item) => item.id === activeHdrDarkCalibrationId);
        const selectedFlat = sessions.find((item) => item.id === activeHdrFlatCalibrationId);
        if (selectedDark && !matchesCurrentImageSpec(selectedDark)) {
            toast.error(`HDR暗场尺寸 ${selectedDark.width}×${selectedDark.height} 与当前全局规格 ${connectionForm.expectedWidth}×${connectionForm.expectedHeight} 不一致`);
            return;
        }
        if (selectedFlat && !matchesCurrentImageSpec(selectedFlat)) {
            toast.error(`HDR平场尺寸 ${selectedFlat.width}×${selectedFlat.height} 与当前全局规格 ${connectionForm.expectedWidth}×${connectionForm.expectedHeight} 不一致`);
            return;
        }

        setLoadingAction("hdr-global-settings");
        try {
            const settings = await jniBridgeService.updateCalibrationGlobalSettings({
                enabled: globalSettings?.enabled ?? false,
                darkCalibrationId: activeDarkCalibrationId,
                flatCalibrationId: activeFlatCalibrationId,
                defectMapEnabled: globalSettings?.defectMapEnabled ?? false,
                hdrEnabled: true,
                hdrDarkCalibrationId: activeHdrDarkCalibrationId,
                hdrFlatCalibrationId: activeHdrFlatCalibrationId,
                hdrDefectMapEnabled: true,
            });
            setGlobalSettings(settings);
            setActiveHdrDarkCalibrationId(settings.hdrDarkCalibrationId);
            setActiveHdrFlatCalibrationId(settings.hdrFlatCalibrationId);
            toast.success("HDR校准包已保存并启用");
        } catch (error) {
            toast.error(error instanceof Error ? error.message : "保存HDR校准包失败");
        } finally {
            setLoadingAction(null);
        }
    };

    const handleDisableHdrCalibrationPackage = async (): Promise<void> => {
        setLoadingAction("hdr-global-settings-disable");
        try {
            const settings = await jniBridgeService.updateCalibrationGlobalSettings({
                enabled: globalSettings?.enabled ?? false,
                darkCalibrationId: activeDarkCalibrationId,
                flatCalibrationId: activeFlatCalibrationId,
                defectMapEnabled: globalSettings?.defectMapEnabled ?? false,
                hdrEnabled: false,
                hdrDarkCalibrationId: activeHdrDarkCalibrationId,
                hdrFlatCalibrationId: activeHdrFlatCalibrationId,
                hdrDefectMapEnabled: false,
            });
            setGlobalSettings(settings);
            setActiveHdrDarkCalibrationId(settings.hdrDarkCalibrationId);
            setActiveHdrFlatCalibrationId(settings.hdrFlatCalibrationId);
            toast.success("HDR校准包已停用，后续HDR采集将使用未校准HG/LG融合");
        } catch (error) {
            toast.error(error instanceof Error ? error.message : "停用HDR校准包失败");
        } finally {
            setLoadingAction(null);
        }
    };

    const darkSessions = sessions.filter((item) => item.calibrationType === "DARK");
    const flatSessions = sessions.filter((item) => item.calibrationType === "FLAT");
    const hdrDarkSessions = sessions.filter((item) => item.calibrationType === "HDR_DARK");
    const hdrFlatSessions = sessions.filter((item) => item.calibrationType === "HDR_FLAT");

    const buildZoomTitle = (): string => {
        if (!zoomPreview) {
            return "校准图像放大查看";
        }
        const prefix = calibrationPrefix(zoomPreview.type);
        const typeLabel = calibrationLabel(zoomPreview.type);
        const sessionLabel = zoomPreview.session
            ? `${prefix}-${String(zoomPreview.session.sessionNumber).padStart(3, "0")}`
            : "未选择会话";
        if (String(zoomPreview.preview.previewType || "").includes("REFERENCE")) {
            return `${typeLabel}最终参考图放大查看 · ${sessionLabel}`;
        }
        return `${typeLabel}样本放大查看 · ${sessionLabel} · ${zoomPreview.preview.label || `第 ${zoomPreview.preview.frameIndex} 帧`}`;
    };

    const renderCalibrationHistorySection = (
        type: CalibrationType,
        records: CalibrationSessionRecord[],
        previewSession: CalibrationSessionRecord | null,
        referencePreviews: CalibrationPreviewRecord[],
        previews: CalibrationPreviewRecord[],
    ) => {
        const isDark = isDarkCalibrationType(type);
        const isHdr = isHdrCalibrationType(type);
        const title = `历史${calibrationLabel(type)}图与${calibrationLabel(type)}包`;
        const prefix = calibrationPrefix(type);
        const hdrPlane = hdrPlaneViewOf(type);
        const hdrPlanePreviews = isHdr
            ? previews.filter((preview) => preview.previewType === `${hdrPlane}_SAMPLE`)
            : [];
        const hdrFallbackPreviews = isHdr
            ? previews.filter((preview) => !preview.previewType || preview.previewType === "SAMPLE")
            : [];
        const usingHdrFallbackPreview = isHdr && hdrPlanePreviews.length === 0 && hdrFallbackPreviews.length > 0;
        const visiblePreviews = isHdr
            ? (hdrPlanePreviews.length > 0 ? hdrPlanePreviews : hdrFallbackPreviews)
            : previews;
        const accentClass = isHdr
            ? "border-purple-200 bg-purple-50"
            : isDark ? "border-slate-300 bg-slate-50" : "border-amber-200 bg-amber-50";
        const previewNote = isHdr
            ? usingHdrFallbackPreview
                ? `${calibrationLabel(type)}样本理论上应包含HG/LG两个原始平面；当前只拿到了旧版HDR诊断合成样本，先临时显示它。重启后端或重新采集新包后，可切换查看HG/LG原始样本。`
                : `${calibrationLabel(type)}样本包含HG/LG两个原始平面；当前显示${hdrPlane}平面原始采集样本，最终校准也会按HG/LG分别生成参考图。`
            : isDark
            ? "暗场样本应在遮光条件下采集，只用于估计暗电流、偏置和固定噪声，不提供图像处理或光谱提取。"
            : "平场样本应在均匀照明条件下采集，只用于估计像素响应不一致和照明不均匀，不提供图像处理或光谱提取。";
        return (
            <Card
                title={<span className="flex items-center gap-2"><RefreshCw size={18} />{title}</span>}
                className={`border ${accentClass}`}
            >
                <div className="space-y-4">
                    <div className="rounded-xl border border-white/80 bg-white p-3">
                        <div className="mb-3">
                            <Text className="block text-sm font-medium text-slate-700">
                                历史{calibrationLabel(type)}包列表
                            </Text>
                            <Text className="text-xs leading-5 text-slate-500">
                                点击表格行即可选中该包；下方会展示最终参考图和详细参数，再往下展示全部原始校准样本。
                            </Text>
                        </div>
                        <Table<CalibrationSessionRecord>
                            rowKey="id"
                            size="small"
                            pagination={{ pageSize: 5, showSizeChanger: false }}
                            dataSource={records}
                            rowClassName={(record) =>
                                `cursor-pointer ${record.id === previewSession?.id ? "bg-cyan-50" : ""}`
                            }
                            onRow={(record) => ({
                                onClick: () => {
                                    void showCalibrationPreview(record);
                                },
                            })}
                            columns={[
                                {
                                    title: `${calibrationLabel(type)}编号`,
                                    dataIndex: "sessionNumber",
                                    render: (value: number) => `${prefix}-${String(value).padStart(3, "0")}`,
                                },
                                { title: "状态", dataIndex: "status", render: (value: string) => <Tag color={statusColor(value)}>{value}</Tag> },
                                { title: "帧数", dataIndex: "frameCount" },
                                {
                                    title: "校准质量",
                                    key: "calibrationQuality",
                                    render: (_: unknown, record: CalibrationSessionRecord) => {
                                        const status = calibrationQualityStatusOf(record);
                                        return (
                                            <Tag color={calibrationQualityColor(status)} className="m-0">
                                                {status ?? "未评价"}
                                            </Tag>
                                        );
                                    },
                                },
                                {
                                    title: "操作",
                                    key: "actions",
                                    render: (_: unknown, record: CalibrationSessionRecord) => (
                                        <Space size={4} wrap>
                                            <Button
                                                size="small"
                                                icon={<Eye size={14} />}
                                                onClick={(event) => {
                                                    event.stopPropagation();
                                                    void showCalibrationPreview(record);
                                                }}
                                            >
                                                查看样本
                                            </Button>
                                            <Button
                                                danger
                                                size="small"
                                                icon={<Trash2 size={14} />}
                                                loading={loadingAction === `delete-${record.id}`}
                                                disabled={loadingAction !== null && loadingAction !== `delete-${record.id}`}
                                                onClick={(event) => {
                                                    event.stopPropagation();
                                                    handleDeleteCalibration(record);
                                                }}
                                            >
                                                删除
                                            </Button>
                                        </Space>
                                    ),
                                },
                            ]}
                            locale={{ emptyText: `还没有${calibrationLabel(type)}校准会话` }}
                        />
                    </div>

                    {previewSession ? (
                        <div className="grid gap-4 xl:grid-cols-[minmax(460px,1.05fr)_minmax(360px,0.95fr)]">
                                {renderReferencePreviewCard(type, previewSession, referencePreviews)}
                            <div className="rounded-lg border border-white/70 bg-white p-3">
                                <div className="mb-3">
                                    <Text className="block text-sm font-medium text-slate-700">
                                        当前选中{calibrationLabel(type)}包参数
                                    </Text>
                                    <Text className="text-xs leading-5 text-slate-500">
                                        这里展示该包的编号、用途、校准样本质量评价和稳定缺陷地图结果。
                                    </Text>
                                </div>
                                <div className="mb-2 flex flex-wrap items-center gap-2">
                                    <Tag color={isDark ? "default" : "gold"} className="m-0">
                                        {prefix}-{String(previewSession.sessionNumber).padStart(3, "0")}
                                    </Tag>
                                    <Tag color={statusColor(previewSession.status)} className="m-0">
                                        {previewSession.status}
                                    </Tag>
                                    <Tag color="blue" className="m-0">
                                        {previewSession.width}×{previewSession.height}
                                    </Tag>
                                    <Tag color={calibrationQualityColor(calibrationQualityStatusOf(previewSession))} className="m-0">
                                        校准质量 {calibrationQualityStatusOf(previewSession) ?? "未评价"}
                                    </Tag>
                                </div>
                                {isHdr && (
                                    <div className="mb-3 flex flex-wrap items-center justify-between gap-2 rounded-lg border border-purple-100 bg-purple-50/70 px-3 py-2">
                                        <Text className="text-xs text-slate-600">
                                            HDR参数按输入平面分别计算，当前查看 {hdrPlane}。
                                        </Text>
                                        <Segmented
                                            size="small"
                                            value={hdrPlane}
                                            options={[
                                                { label: "HG高增益", value: "HG" },
                                                { label: "LG低增益", value: "LG" },
                                            ]}
                                            onChange={(value) => setHdrPlaneView(type, value as HdrPlaneView)}
                                        />
                                    </div>
                                )}
                                <div className="grid grid-cols-2 gap-2 text-xs text-slate-600">
                                    <div>
                                        <Text className="block text-[11px] text-slate-400">帧数</Text>
                                        {previewSession.frameCount}/{previewSession.expectedFrameCount}
                                    </div>
                                    <div>
                                        <Text className="block text-[11px] text-slate-400">用途</Text>
                                        {isHdr ? "HDR分平面参考" : isDark ? "暗场参考" : "平场参考"}
                                    </div>
                                </div>
                                <div className="mt-3 space-y-3">
                                    {renderCalibrationQualitySummary(type, previewSession)}
                                    {renderDefectMapSummary(previewSession)}
                                </div>
                            </div>
                        </div>
                    ) : (
                        <div className="rounded-lg border border-dashed border-slate-300 bg-white/60 p-4 text-sm text-slate-400">
                            暂无选中的{calibrationLabel(type)}包。请在上方历史列表中选择。
                        </div>
                    )}

                    <div className="rounded-xl border border-white/80 bg-white p-3">
                        <div className="mb-3 flex flex-wrap items-start justify-between gap-3">
                            <div>
                                <Text className="block text-sm font-medium text-slate-700">
                                    原始校准样本预览
                                </Text>
                                <Text className="text-xs leading-5 text-slate-500">
                                    {previewNote}
                                </Text>
                            </div>
                            <Space size={8} wrap>
                                <Tag color="cyan" className="m-0">
                                    已加载 {visiblePreviews.length} 张
                                </Tag>
                                {isHdr && (
                                    <Segmented
                                        size="small"
                                        value={hdrPlane}
                                        options={[
                                            { label: "HG高增益样本", value: "HG" },
                                            { label: "LG低增益样本", value: "LG" },
                                        ]}
                                        onChange={(value) => setHdrPlaneView(type, value as HdrPlaneView)}
                                    />
                                )}
                            </Space>
                        </div>
                        {usingHdrFallbackPreview && (
                            <div className="mb-3 rounded-lg border border-orange-200 bg-orange-50 px-3 py-2 text-xs leading-5 text-orange-700">
                                当前选中包没有返回 {hdrPlane}_SAMPLE 分平面样本，已兼容显示旧版 HDR 诊断合成样本；如果你刚刚更新过程序，请重启 Java 后端并重新打开该页面。
                            </div>
                        )}
                        {visiblePreviews.length > 0 ? (
                            <div className="overflow-x-auto pb-2">
                                <div className="inline-grid w-max grid-flow-col grid-rows-2 gap-3 auto-cols-[minmax(280px,320px)]">
                                {visiblePreviews.map((preview) => (
                                    <button
                                        type="button"
                                        key={`${preview.previewType}-${preview.frameIndex}`}
                                        className="group overflow-hidden rounded-lg border border-slate-200 bg-slate-950 text-left shadow-sm transition hover:border-cyan-300 hover:shadow-md"
                                        title="点击放大查看"
                                        onClick={() => openCalibrationPreviewZoom(type, previewSession, preview)}
                                    >
                                        <div className="flex aspect-[4/3] items-center justify-center p-2">
                                            <img
                                                src={preview.imageDataUrl}
                                                alt={`${calibrationLabel(type)}第${preview.frameIndex}帧`}
                                                className="h-full w-full cursor-zoom-in object-contain"
                                            />
                                        </div>
                                        <div className="flex items-center justify-between border-t border-slate-800 bg-slate-900 px-2 py-1 text-xs">
                                            <span className="text-slate-300">第 {preview.frameIndex} 帧</span>
                                            <Tag color={isDark ? "default" : "gold"} className="m-0 text-[11px]">
                                                {usingHdrFallbackPreview ? "HDR诊断合成样本" : isHdr ? `${hdrPlane} RAW校准样本` : "RAW校准样本"}
                                            </Tag>
                                        </div>
                                        <div className="border-t border-slate-800 bg-slate-900/95 px-2 pb-1 text-[11px] text-cyan-200 opacity-0 transition group-hover:opacity-100">
                                            点击放大
                                        </div>
                                    </button>
                                ))}
                                </div>
                            </div>
                        ) : (
                            <div className="flex min-h-[260px] items-center justify-center rounded-lg border border-dashed border-slate-300 bg-slate-50 text-sm text-slate-400">
                                选择一个{calibrationLabel(type)}包后展示原始校准样本。
                            </div>
                        )}
                    </div>
                </div>
            </Card>
        );
    };

    return (
        <div className="space-y-5">
            <div>
                <Title level={3} className="!mb-1 !text-slate-800">
                    {isHdrMode ? "HDR暗场、HDR平场与HG/LG稳定缺陷修复校准" : "暗场、平场与稳定缺陷修复校准"}
                </Title>
                <Text className="text-slate-500">
                    {isHdrMode
                        ? "HDR校准数据独立保存；HG/LG参考图分别作用于对应输入平面，然后再进行HDR融合。"
                        : "普通校准数据独立保存；当前可生成模拟数据，也可连续采集真实 CMOS 帧进行替换。"}
                </Text>
            </div>

            {!isHdrMode && <Card size="small" className="border border-cyan-200 bg-cyan-50">
                <div className="flex flex-wrap items-center justify-between gap-4">
                    <div>
                        <Text className="block font-medium text-slate-800">当前校准包与稳定缺陷修复</Text>
                        <Text className="mt-1 block text-xs leading-5 text-slate-600">
                            {globalSettings?.message || "正在读取当前用户的全局校准状态…"}
                        </Text>
                        <div className="mt-2 flex flex-wrap gap-2">
                            <Tag color={globalSettings?.enabled ? "green" : "default"}>
                                {globalSettings?.enabled ? "全局已启用" : "全局未启用"}
                            </Tag>
                            <Tag color={globalSettings?.calibrationPackageReady ? "green" : "default"}>
                                校准包 {globalSettings?.calibrationPackageReady ? "READY" : "未就绪"}
                            </Tag>
                            <Tag color={globalSettings?.defectMapAvailable ? "green" : "default"}>
                                稳定缺陷地图 {globalSettings?.defectMapAvailable ? "可用" : "无有效条目"}
                            </Tag>
                        </div>
                    </div>
                </div>
                <div className="mt-4 grid gap-3 md:grid-cols-2 xl:grid-cols-[1fr_1fr_auto]">
                    <Select
                        value={activeDarkCalibrationId ?? undefined}
                        placeholder="选择 READY 暗场会话"
                        options={sessions
                            .filter((item) => item.calibrationType === "DARK" && item.status === "READY")
                            .map((item) => ({
                                value: item.id,
                                disabled: !matchesCurrentImageSpec(item),
                                label: `暗场 D-${String(item.sessionNumber).padStart(3, "0")} · ${item.width}×${item.height} · 质量${calibrationQualityStatusOf(item) ?? "未评价"}${matchesCurrentImageSpec(item) ? "" : " · 尺寸不匹配"}`,
                            }))}
                        disabled={loadingAction !== null}
                        onChange={(value) => setActiveDarkCalibrationId(typeof value === "number" ? value : null)}
                    />
                    <Select
                        value={activeFlatCalibrationId ?? undefined}
                        placeholder="选择 READY 平场会话"
                        options={sessions
                            .filter((item) => item.calibrationType === "FLAT" && item.status === "READY")
                            .map((item) => ({
                                value: item.id,
                                disabled: !matchesCurrentImageSpec(item),
                                label: `平场 F-${String(item.sessionNumber).padStart(3, "0")} · ${item.width}×${item.height} · 质量${calibrationQualityStatusOf(item) ?? "未评价"}${matchesCurrentImageSpec(item) ? "" : " · 尺寸不匹配"}`,
                            }))}
                        disabled={loadingAction !== null}
                        onChange={(value) => setActiveFlatCalibrationId(typeof value === "number" ? value : null)}
                    />
                    <Space wrap>
                        <Button
                            type="primary"
                            loading={loadingAction === "global-settings"}
                            disabled={loadingAction !== null || Boolean(globalSettings?.enabled)}
                            title={globalSettings?.enabled ? "当前校准包已启用，如需重新保存请先停用校准包" : "保存并启用当前选择的校准包，同时启用稳定缺陷修复"}
                            onClick={() => void handleSaveCalibrationPackage()}
                        >
                            保存并启用校准包与缺陷修复
                        </Button>
                        <Button
                            danger
                            loading={loadingAction === "global-settings-disable"}
                            disabled={loadingAction !== null || !globalSettings?.enabled}
                            onClick={() => void handleDisableCalibrationPackage()}
                        >
                            停用校准包
                        </Button>
                    </Space>
                </div>
            </Card>}

            {isHdrMode && <Card size="small" className="border border-purple-200 bg-purple-50">
                <div className="flex flex-wrap items-center justify-between gap-4">
                    <div>
                        <Text className="block font-medium text-slate-800">HDR校准包与HG/LG稳定缺陷修复</Text>
                        <Text className="mt-1 block text-xs leading-5 text-slate-600">
                            HDR模式使用独立校准包：HG先用HG暗场/平场校准，LG先用LG暗场/平场校准，再进行HDR融合。
                        </Text>
                        <div className="mt-2 flex flex-wrap gap-2">
                            <Tag color={globalSettings?.hdrEnabled ? "green" : "default"}>
                                {globalSettings?.hdrEnabled ? "HDR已启用" : "HDR未启用"}
                            </Tag>
                            <Tag color={globalSettings?.hdrCalibrationPackageReady ? "green" : "default"}>
                                HDR校准包 {globalSettings?.hdrCalibrationPackageReady ? "READY" : "未就绪"}
                            </Tag>
                            <Tag color={globalSettings?.hdrDefectMapAvailable ? "green" : "default"}>
                                HDR稳定缺陷地图 {globalSettings?.hdrDefectMapAvailable ? "可用" : "无有效条目"}
                            </Tag>
                        </div>
                    </div>
                </div>
                <div className="mt-4 grid gap-3 md:grid-cols-2 xl:grid-cols-[1fr_1fr_auto]">
                    <Select
                        value={activeHdrDarkCalibrationId ?? undefined}
                        placeholder="选择 READY HDR暗场会话"
                        options={sessions
                            .filter((item) => item.calibrationType === "HDR_DARK" && item.status === "READY")
                            .map((item) => ({
                                value: item.id,
                                disabled: !matchesCurrentImageSpec(item),
                                label: `HDR暗场 HD-${String(item.sessionNumber).padStart(3, "0")} · ${item.width}×${item.height} · 质量${calibrationQualityStatusOf(item) ?? "未评价"}${matchesCurrentImageSpec(item) ? "" : " · 尺寸不匹配"}`,
                            }))}
                        disabled={loadingAction !== null}
                        onChange={(value) => setActiveHdrDarkCalibrationId(typeof value === "number" ? value : null)}
                    />
                    <Select
                        value={activeHdrFlatCalibrationId ?? undefined}
                        placeholder="选择 READY HDR平场会话"
                        options={sessions
                            .filter((item) => item.calibrationType === "HDR_FLAT" && item.status === "READY")
                            .map((item) => ({
                                value: item.id,
                                disabled: !matchesCurrentImageSpec(item),
                                label: `HDR平场 HF-${String(item.sessionNumber).padStart(3, "0")} · ${item.width}×${item.height} · 质量${calibrationQualityStatusOf(item) ?? "未评价"}${matchesCurrentImageSpec(item) ? "" : " · 尺寸不匹配"}`,
                            }))}
                        disabled={loadingAction !== null}
                        onChange={(value) => setActiveHdrFlatCalibrationId(typeof value === "number" ? value : null)}
                    />
                    <Space wrap>
                        <Button
                            type="primary"
                            loading={loadingAction === "hdr-global-settings"}
                            disabled={loadingAction !== null || Boolean(globalSettings?.hdrEnabled)}
                            title={globalSettings?.hdrEnabled ? "当前HDR校准包已启用，如需重新保存请先停用HDR校准包" : "保存并启用当前HDR校准包，同时启用HG/LG稳定缺陷修复"}
                            onClick={() => void handleSaveHdrCalibrationPackage()}
                        >
                            保存并启用HDR校准包与缺陷修复
                        </Button>
                        <Button
                            danger
                            loading={loadingAction === "hdr-global-settings-disable"}
                            disabled={loadingAction !== null || !globalSettings?.hdrEnabled}
                            onClick={() => void handleDisableHdrCalibrationPackage()}
                        >
                            停用HDR校准包
                        </Button>
                    </Space>
                </div>
            </Card>}

            <div>
                <Card title={<span className="flex items-center gap-2"><Beaker size={18} />校准数据采集与保存</span>}>
                    <div className="grid gap-4 md:grid-cols-2">
                        <div>
                            <Text className="mb-2 block text-sm text-slate-600">校准类型</Text>
                            <Select
                                className="w-full"
                                value={calibrationType}
                                options={activeCalibrationOptions}
                                onChange={(value) => setCalibrationType(value as CalibrationType)}
                                disabled={loadingAction !== null}
                            />
                        </div>
                        <div>
                            <Text className="mb-2 block text-sm text-slate-600">采集帧数</Text>
                            <InputNumber
                                className="w-full"
                                min={2}
                                max={64}
                                value={frameCount}
                                onChange={(value) => setFrameCount(Number(value ?? 2))}
                                disabled={loadingAction !== null}
                            />
                        </div>
                        <div>
                            <Text className="mb-2 block text-sm text-slate-600">帧间隔（毫秒）</Text>
                            <InputNumber
                                className="w-full"
                                min={0}
                                max={60000}
                                value={intervalMs}
                                onChange={(value) => setIntervalMs(Number(value ?? 0))}
                                disabled={loadingAction !== null}
                            />
                            <Text className="mt-1 block text-xs text-slate-400">真实使用可设置为 8000；测试时可设置为 0。</Text>
                        </div>
                        <div>
                            <Text className="mb-2 block text-sm text-slate-600">当前设备</Text>
                            <Tag color={bridgeState.connected ? "green" : "default"}>
                                {bridgeState.connected ? "已连接，可硬件采集" : "未连接，仅可生成模拟数据"}
                            </Tag>
                        </div>
                    </div>

                    <Divider />
                    <Space wrap>
                        <Button
                            icon={<Settings2 size={16} />}
                            loading={loadingAction === "simulate"}
                            disabled={loadingAction !== null}
                            onClick={handleSimulate}
                        >
                            生成模拟{calibrationLabel(calibrationType)}
                        </Button>
                        <Button
                            type="primary"
                            icon={<Save size={16} />}
                            loading={loadingAction === "calibration"}
                            disabled={!bridgeState.connected || loadingAction !== null}
                            onClick={handleCollectCalibration}
                        >
                            连续采集并保存校准
                        </Button>
                        {loadingAction !== null && loadingAction !== "simulate" && (
                            <Button danger icon={<CircleStop size={16} />} onClick={() => { stopRef.current = true; }}>
                                停止后续采集
                            </Button>
                        )}
                    </Space>
                    <Text className="mt-3 block text-xs text-slate-500">
                        最近采集到的帧：{collectedIds.length ? collectedIds.join(", ") : "暂无"}
                    </Text>
                </Card>

            </div>

            <div className="space-y-5">
                {isHdrMode ? (
                    <>
                        {renderCalibrationHistorySection("HDR_DARK", hdrDarkSessions, hdrDarkPreviewSession, hdrDarkReferencePreviews, hdrDarkPreviews)}
                        {renderCalibrationHistorySection("HDR_FLAT", hdrFlatSessions, hdrFlatPreviewSession, hdrFlatReferencePreviews, hdrFlatPreviews)}
                    </>
                ) : (
                    <>
                        {renderCalibrationHistorySection("DARK", darkSessions, darkPreviewSession, darkReferencePreviews, darkPreviews)}
                        {renderCalibrationHistorySection("FLAT", flatSessions, flatPreviewSession, flatReferencePreviews, flatPreviews)}
                    </>
                )}
            </div>

            <Modal
                title={buildZoomTitle()}
                open={Boolean(zoomPreview)}
                onCancel={() => setZoomPreview(null)}
                footer={[
                    <Button key="zoomOut" onClick={() => setZoomScale((value) => clampPreviewZoom(value / 1.25))}>
                        缩小
                    </Button>,
                    <Button key="reset" onClick={() => setZoomScale(1)}>
                        100%
                    </Button>,
                    <Button key="zoomIn" onClick={() => setZoomScale((value) => clampPreviewZoom(value * 1.25))}>
                        放大
                    </Button>,
                    <Button key="close" type="primary" onClick={() => setZoomPreview(null)}>
                        关闭
                    </Button>,
                ]}
                width="96vw"
                style={{ maxWidth: 1500, top: 22 }}
                destroyOnClose
            >
                <div className="space-y-3">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                        <Text className="text-xs leading-5 text-slate-500">
                            {String(zoomPreview?.preview.previewType || "").includes("REFERENCE")
                                ? "这是多帧样本逐像素取中位数后生成的最终参考图，后续正式图像校准会实际读取它。"
                                : "暗场/平场样本是校准 RAW 预览，用于追溯最终参考图的来源，不参与图像处理和光谱提取。"}
                        </Text>
                        <Tag color="purple" className="m-0">
                            缩放 {(zoomScale * 100).toFixed(0)}%
                        </Tag>
                    </div>
                    <div
                        className="h-[76vh] overflow-auto rounded-lg bg-slate-950 p-4 text-center"
                        onWheel={(event) => {
                            event.preventDefault();
                            const factor = event.deltaY < 0 ? 1.12 : 0.88;
                            setZoomScale((value) => clampPreviewZoom(value * factor));
                        }}
                    >
                        {zoomPreview && (
                            <img
                                src={zoomPreview.preview.imageDataUrl}
                                alt={zoomPreview.preview.label || buildZoomTitle()}
                                className="inline-block select-none object-contain align-middle"
                                style={{
                                    width: `${zoomScale * 100}%`,
                                    maxWidth: zoomScale <= 1 ? "100%" : "none",
                                }}
                                draggable={false}
                            />
                        )}
                    </div>
                    <Text className="block text-xs text-slate-500">
                        提示：在图像区域滚轮缩放；放大后可用滚动条查看局部细节。
                    </Text>
                </div>
            </Modal>
        </div>
    );
};

export default CalibrationPage;
