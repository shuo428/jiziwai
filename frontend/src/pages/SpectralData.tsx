import React, { useEffect, useMemo, useState } from "react";
import { Alert, Button, Card, Checkbox, Input, InputNumber, Select, Tag, Typography } from "antd";
import {
    Activity,
    AlertCircle,
    Binary,
    Camera,
    CheckCircle,
    Plug,
    RotateCcw,
    Send,
    Settings,
    Unplug,
} from "lucide-react";
import { toast } from "sonner";

import { jniBridgeService } from "../service/jniBridgeService";
import ImagePixelDataViewer from "../components/ImagePixelDataViewer";
import SpectrumCurve from "../components/SpectrumCurve";
import { useJNIStore } from "../store/jniStore";
import type {
    CalibrationGlobalSettingsRecord,
    ImageFrameRecord,
    SpectrumExtractionRecord,
    SpectrumExtractionRequest,
    SpectrumRoi,
} from "../types/jni";

const { Title, Text } = Typography;

const CONFIG_BYTE_COUNT = 512;
const CONFIG_HEX_LENGTH = CONFIG_BYTE_COUNT * 2;
const byteIndexes = Array.from({ length: CONFIG_BYTE_COUNT }, (_, index) => index);

const normalizeHexText = (value: string): string =>
    value.replace(/0x/gi, "").replace(/[^0-9a-fA-F]/g, "").toUpperCase();

const parseLittleEndianHexBytes = (value: string): number[] => {
    const normalized = normalizeHexText(value);
    if (normalized.length !== CONFIG_HEX_LENGTH) {
        throw new Error(`需要 ${CONFIG_HEX_LENGTH} 个十六进制字符，当前为 ${normalized.length} 个`);
    }

    const parsedBytes: number[] = [];
    for (let cursor = normalized.length; cursor > 0; cursor -= 2) {
        parsedBytes.push(Number.parseInt(normalized.slice(cursor - 2, cursor), 16));
    }
    return parsedBytes;
};

const toLittleEndianHexText = (bytes: number[]): string =>
    bytes
        .slice(0, CONFIG_BYTE_COUNT)
        .map((value) => Math.max(0, Math.min(255, Math.trunc(value))).toString(16).padStart(2, "0").toUpperCase())
        .reverse()
        .join("");

const qualityColor = (status?: string | null): string => {
    if (status === "PASS") {
        return "green";
    }
    if (status === "WARNING") {
        return "orange";
    }
    if (status === "FAIL") {
        return "red";
    }
    return "default";
};

const formatNullableNumber = (value: number | null, digits = 2): string =>
    typeof value === "number" ? value.toFixed(digits) : "-";

const formatNullableRatio = (value: number | null): string =>
    typeof value === "number" ? `${(value * 100).toFixed(4)}%` : "-";

type MetricStatus = "PASS" | "WARNING" | "FAIL" | "INFO";

type QualityMetricExplanation = {
    key: string;
    label: string;
    value: string;
    healthyCondition: string;
    unhealthyReason: string;
    reference: string;
    status: MetricStatus;
};

type QualityMetricSource = {
    qualityStatus: string | null;
    pixelMin: number | null;
    pixelMax: number | null;
    pixelMean: number | null;
    pixelStddev: number | null;
    blackPixelRatio: number | null;
    saturationPixelRatio: number | null;
    abnormalRowCount: number | null;
    abnormalColumnCount: number | null;
    badPixelCount: number | null;
    qualitySummaryMessage: string | null;
    qualityDetails: Record<string, unknown> | null;
};

type QualityViewMode = "before" | "after";

const numberFromRecord = (record: Record<string, unknown> | null | undefined, key: string): number | null => {
    const value = record?.[key];
    return typeof value === "number" ? value : null;
};

const stringFromRecord = (record: Record<string, unknown> | null | undefined, key: string): string | null => {
    const value = record?.[key];
    return typeof value === "string" ? value : null;
};

const booleanFromRecord = (record: Record<string, unknown> | null | undefined, key: string): boolean | null => {
    const value = record?.[key];
    return typeof value === "boolean" ? value : null;
};

const objectFromRecord = (
    record: Record<string, unknown> | null | undefined,
    key: string,
): Record<string, unknown> | null => {
    const value = record?.[key];
    return value && typeof value === "object" ? (value as Record<string, unknown>) : null;
};

const calibrationReferenceLabel = (
    record: Record<string, unknown> | null | undefined,
    labelKey: string,
    sessionNumberKey: string,
    idKey: string,
    prefix: "D" | "F",
): string => {
    const label = stringFromRecord(record, labelKey);
    if (label) {
        return label;
    }
    const sessionNumber = numberFromRecord(record, sessionNumberKey);
    if (sessionNumber !== null) {
        return `${prefix}-${String(sessionNumber).padStart(3, "0")}`;
    }
    const id = numberFromRecord(record, idKey);
    return id === null ? "-" : `ID ${id}`;
};

const metricColor = (status: MetricStatus): string => {
    if (status === "PASS") {
        return "green";
    }
    if (status === "WARNING") {
        return "orange";
    }
    if (status === "FAIL") {
        return "red";
    }
    return "blue";
};

const dispositionColor = (status?: string | null): string => {
    if (status === "USE_AS_IS") {
        return "green";
    }
    if (status === "PROCESS_REQUIRED" || status === "MANUAL_REVIEW") {
        return "orange";
    }
    if (status === "RECAPTURE_RECOMMENDED" || status === "REJECTED") {
        return "red";
    }
    return "default";
};

const canExtractSpectrum = (frame: ImageFrameRecord | null): boolean =>
    frame?.qualityStatus === "PASS" || frame?.processedQualityStatus === "PASS";

const getSpectrumExtractionDisabledReason = (frame: ImageFrameRecord | null): string | null => {
    if (!frame) {
        return "暂无图像可提取，请先获取一帧图片。";
    }
    if (!canExtractSpectrum(frame)) {
        return "只有原图质量为PASS，或处理后复检质量为PASS的图像，才能提取一维光谱。";
    }
    return null;
};

const sanitizeRoiDraft = (roi: Partial<SpectrumRoi>): SpectrumExtractionRequest["roi"] => {
    const result: Partial<SpectrumRoi> = {};
    (["xStart", "xEnd", "yStart", "yEnd"] as const).forEach((key) => {
        const value = roi[key];
        if (typeof value === "number" && Number.isFinite(value)) {
            result[key] = Math.trunc(value);
        }
    });
    return Object.keys(result).length > 0 ? result : undefined;
};

const actionColor = (severity?: string | null): string => {
    if (severity === "FAIL") {
        return "red";
    }
    if (severity === "WARNING") {
        return "orange";
    }
    if (severity === "INFO") {
        return "blue";
    }
    return "default";
};

const processingColor = (status?: string | null): string => {
    if (status === "PROCESSED") {
        return "green";
    }
    if (status === "SKIPPED") {
        return "blue";
    }
    if (status === "FAILED") {
        return "red";
    }
    return "default";
};

const processingLabel = (status?: string | null): string => {
    if (status === "PROCESSED") {
        return "已处理";
    }
    if (status === "SKIPPED") {
        return "已跳过";
    }
    if (status === "FAILED") {
        return "处理失败";
    }
    return "未处理";
};

type ProcessingDisplay = {
    label: string;
    color: string;
};

const isProcessedFrame = (frame: ImageFrameRecord | null): boolean =>
    Boolean(frame?.processedImageDataUrl) || frame?.processingStatus === "PROCESSED";

const hasExecutableProcessingAction = (frame: ImageFrameRecord | null): boolean =>
    Boolean(
        frame?.recommendedActions.some(
            (action) =>
                action.stage === "PROCESS" &&
                action.repairable &&
                (action.code === "BAD_PIXEL_INTERPOLATION" || action.code === "ABNORMAL_LINE_CORRECTION"),
        ),
    );

const getProcessingDisabledReason = (frame: ImageFrameRecord | null): string | null => {
    if (!frame) {
        return "暂无图像可处理，请先获取一帧图片。";
    }
    if (isProcessedFrame(frame)) {
        return "当前图片已完成图像处理，无需重复处理。";
    }
    if (frame.processingStatus === "SKIPPED") {
        return frame.processingMessage || "当前图片没有可自动修复动作，或无需修复。";
    }
    if (
        frame.qualityStatus === "FAIL" ||
        frame.dispositionStatus === "RECAPTURE_RECOMMENDED" ||
        frame.dispositionStatus === "REJECTED"
    ) {
        return "当前图片质量为FAIL或建议重采，自动处理不会恢复真实光谱信息，请重新采集。";
    }
    if (!hasExecutableProcessingAction(frame)) {
        return "当前图片没有可自动处理的坏点或少量异常行/列。";
    }
    return null;
};

const getProcessingButtonLabel = (frame: ImageFrameRecord | null, actionLabel: string): string => {
    if (!frame) {
        return actionLabel;
    }
    if (isProcessedFrame(frame)) {
        return "已处理";
    }
    if (getProcessingDisabledReason(frame)) {
        return "不可处理";
    }
    return actionLabel;
};

const getProcessingDisplay = (frame: ImageFrameRecord | null): ProcessingDisplay => {
    if (!frame) {
        return { label: "未处理", color: "default" };
    }
    if (isProcessedFrame(frame)) {
        return { label: "已处理", color: "green" };
    }
    if (frame.processingStatus === "SKIPPED") {
        return { label: "已跳过", color: "blue" };
    }
    if (frame.processingStatus === "FAILED") {
        return { label: "处理失败", color: "red" };
    }
    if (
        frame.qualityStatus === "FAIL" ||
        frame.dispositionStatus === "RECAPTURE_RECOMMENDED" ||
        frame.dispositionStatus === "REJECTED"
    ) {
        return { label: "不可处理", color: "red" };
    }
    if (!hasExecutableProcessingAction(frame)) {
        if (frame.qualityStatus === "PASS" || frame.dispositionStatus === "USE_AS_IS") {
            return { label: "无需处理", color: "green" };
        }
        return { label: "不可自动处理", color: "orange" };
    }
    return { label: "未处理", color: "default" };
};

const getQualityThreshold = (
    frame: QualityMetricSource | null,
    key: string,
    fallback: number,
): number => {
    const thresholds = frame?.qualityDetails?.thresholds;
    if (thresholds && typeof thresholds === "object" && key in thresholds) {
        const value = (thresholds as Record<string, unknown>)[key];
        if (typeof value === "number") {
            return value;
        }
    }
    return fallback;
};

const getQualityDecisionReasons = (frame: QualityMetricSource | null): string[] => {
    const reasons = frame?.qualityDetails?.decisionReasons;
    if (Array.isArray(reasons)) {
        return reasons.filter((item): item is string => typeof item === "string");
    }
    return [];
};

const buildQualityMetricExplanations = (frame: QualityMetricSource | null): QualityMetricExplanation[] => {
    if (!frame) {
        return [];
    }

    const blackWarningRatio = getQualityThreshold(frame, "blackWarningRatio", 0.05);
    const blackFailRatio = getQualityThreshold(frame, "blackFailRatio", 0.5);
    const saturationWarningRatio = getQualityThreshold(frame, "saturationWarningRatio", 0.001);
    const saturationFailRatio = getQualityThreshold(frame, "saturationFailRatio", 0.01);
    const dynamicRangeWarningDn = getQualityThreshold(frame, "dynamicRangeWarningDn", 64);
    const dynamicRangeFailDn = getQualityThreshold(frame, "dynamicRangeFailDn", 16);
    const abnormalLineWarningCount = getQualityThreshold(frame, "abnormalLineWarningCount", 1);
    const abnormalLineFailCount = getQualityThreshold(frame, "abnormalLineFailCount", 3);
    const badPixelWarningCount = getQualityThreshold(frame, "badPixelWarningCount", 60);
    const badPixelFailCount = getQualityThreshold(frame, "badPixelFailCount", 500);

    const dynamicRange =
        typeof frame.pixelMin === "number" && typeof frame.pixelMax === "number"
            ? frame.pixelMax - frame.pixelMin
            : null;
    const abnormalLineCount =
        typeof frame.abnormalRowCount === "number" && typeof frame.abnormalColumnCount === "number"
            ? frame.abnormalRowCount + frame.abnormalColumnCount
            : null;

    const ratioStatus = (value: number | null, warning: number, fail: number): MetricStatus => {
        if (typeof value !== "number") {
            return "INFO";
        }
        if (value >= fail) {
            return "FAIL";
        }
        if (value >= warning) {
            return "WARNING";
        }
        return "PASS";
    };

    const dynamicRangeStatus: MetricStatus =
        typeof dynamicRange !== "number"
            ? "INFO"
            : dynamicRange <= dynamicRangeFailDn
              ? "FAIL"
              : dynamicRange <= dynamicRangeWarningDn
                ? "WARNING"
                : "PASS";

    const abnormalLineStatus: MetricStatus =
        typeof abnormalLineCount !== "number"
            ? "INFO"
            : abnormalLineCount >= abnormalLineFailCount
              ? "FAIL"
              : abnormalLineCount >= abnormalLineWarningCount
                ? "WARNING"
                : "PASS";

    const badPixelStatus: MetricStatus =
        typeof frame.badPixelCount !== "number"
            ? "INFO"
            : frame.badPixelCount >= badPixelFailCount
              ? "FAIL"
              : frame.badPixelCount > badPixelWarningCount
                ? "WARNING"
                : "PASS";

    return [
        {
            key: "blackPixelRatio",
            label: "黑像素比例",
            value: formatNullableRatio(frame.blackPixelRatio),
            healthyCondition: `< ${(blackWarningRatio * 100).toFixed(2)}%`,
            unhealthyReason: "黑像素比例过高通常表示欠曝、遮挡、无光或有效信号不足，会降低后续光谱提取的信噪比。",
            reference: `DN ≤ ${getQualityThreshold(frame, "blackThresholdDn", 16)} 计为黑像素；≥ ${(blackWarningRatio * 100).toFixed(2)}% 警告，≥ ${(blackFailRatio * 100).toFixed(2)}% 失败。`,
            status: ratioStatus(frame.blackPixelRatio, blackWarningRatio, blackFailRatio),
        },
        {
            key: "saturationPixelRatio",
            label: "饱和像素比例",
            value: formatNullableRatio(frame.saturationPixelRatio),
            healthyCondition: `< ${(saturationWarningRatio * 100).toFixed(3)}%`,
            unhealthyReason: "饱和像素过多说明 ADC 或像素输出接近满量程，峰强度和局部细节已经被裁剪，定量信息不可恢复。",
            reference: `DN ≥ ${getQualityThreshold(frame, "saturationThresholdDn", 4080)} 计为饱和；≥ ${(saturationWarningRatio * 100).toFixed(3)}% 警告，≥ ${(saturationFailRatio * 100).toFixed(2)}% 失败。`,
            status: ratioStatus(frame.saturationPixelRatio, saturationWarningRatio, saturationFailRatio),
        },
        {
            key: "dynamicRange",
            label: "动态范围",
            value: typeof dynamicRange === "number" ? `${dynamicRange} DN` : "-",
            healthyCondition: `> ${dynamicRangeWarningDn} DN`,
            unhealthyReason: "动态范围过小表示图像接近常数图，常见于无光、曝光不足、遮挡或读出异常。",
            reference: `pixel_max - pixel_min；≤ ${dynamicRangeWarningDn} DN 警告，≤ ${dynamicRangeFailDn} DN 失败。`,
            status: dynamicRangeStatus,
        },
        {
            key: "abnormalLineCount",
            label: "异常行/列",
            value:
                typeof abnormalLineCount === "number"
                    ? `${abnormalLineCount} 条（行 ${frame.abnormalRowCount ?? "-"} / 列 ${frame.abnormalColumnCount ?? "-"}）`
                    : "-",
            healthyCondition: `< ${abnormalLineWarningCount} 条，理想为 0`,
            unhealthyReason: "孤立异常行/列通常反映行列读出链路、传感器列放大器或固定条纹问题，会污染光谱图像的空间一致性。",
            reference: `按行/列均值相对局部中值检测；GLUX1605BSI Grade 1 对缺陷行/列总数要求为 0。当前 ≥ ${abnormalLineWarningCount} 条警告，≥ ${abnormalLineFailCount} 条失败。`,
            status: abnormalLineStatus,
        },
        {
            key: "badPixelCount",
            label: "坏点数量",
            value: typeof frame.badPixelCount === "number" ? `${frame.badPixelCount} 个` : "-",
            healthyCondition: `≤ ${badPixelWarningCount} 个`,
            unhealthyReason: "坏点过多会在图像中形成孤立亮点/暗点，可能在积分提取光谱时形成假峰或抬高噪声。",
            reference: `用 8 邻域中值 + MAD 做单帧局部离群检测；GLUX1605BSI Grade 1 total defect pixels 限值为 60。当前 > ${badPixelWarningCount} 警告，≥ ${badPixelFailCount} 失败。`,
            status: badPixelStatus,
        },
        {
            key: "grayStats",
            label: "灰度统计",
            value: `${frame.pixelMin ?? "-"} / ${frame.pixelMax ?? "-"} / ${formatNullableNumber(frame.pixelMean)} / σ ${formatNullableNumber(frame.pixelStddev)}`,
            healthyCondition: "辅助指标，不单独决定 PASS/FAIL",
            unhealthyReason: "均值和标准差用于解释整体亮度、噪声和对比度；不同光源/曝光下会自然变化，所以不单独设死阈值。",
            reference: "依次为 min / max / mean / stddev，主要配合黑像素比例、饱和比例和动态范围解释图像状态。",
            status: "INFO",
        },
    ];
};

const buildOriginalQualitySource = (frame: ImageFrameRecord | null): QualityMetricSource | null => {
    if (!frame) {
        return null;
    }
    const snapshot = frame.originalQualitySnapshot;
    return {
        qualityStatus: stringFromRecord(snapshot, "qualityStatus") ?? frame.qualityStatus,
        pixelMin: numberFromRecord(snapshot, "pixelMin") ?? frame.pixelMin,
        pixelMax: numberFromRecord(snapshot, "pixelMax") ?? frame.pixelMax,
        pixelMean: numberFromRecord(snapshot, "pixelMean") ?? frame.pixelMean,
        pixelStddev: numberFromRecord(snapshot, "pixelStddev") ?? frame.pixelStddev,
        blackPixelRatio: numberFromRecord(snapshot, "blackPixelRatio") ?? frame.blackPixelRatio,
        saturationPixelRatio: numberFromRecord(snapshot, "saturationPixelRatio") ?? frame.saturationPixelRatio,
        abnormalRowCount: numberFromRecord(snapshot, "abnormalRowCount") ?? frame.abnormalRowCount,
        abnormalColumnCount: numberFromRecord(snapshot, "abnormalColumnCount") ?? frame.abnormalColumnCount,
        badPixelCount: numberFromRecord(snapshot, "badPixelCount") ?? frame.badPixelCount,
        qualitySummaryMessage: stringFromRecord(snapshot, "summaryMessage") ?? frame.qualitySummaryMessage,
        qualityDetails: objectFromRecord(snapshot, "details") ?? frame.qualityDetails,
    };
};

/** 将后端保存的原始 RAW 硬性检查/校准后检查快照转换为统一的指标展示结构。 */
const buildSnapshotQualitySource = (
    snapshot: Record<string, unknown> | null | undefined,
): QualityMetricSource | null => {
    const qualityStatus = stringFromRecord(snapshot, "qualityStatus");
    if (!snapshot || !qualityStatus) {
        return null;
    }
    return {
        qualityStatus,
        pixelMin: numberFromRecord(snapshot, "pixelMin"),
        pixelMax: numberFromRecord(snapshot, "pixelMax"),
        pixelMean: numberFromRecord(snapshot, "pixelMean"),
        pixelStddev: numberFromRecord(snapshot, "pixelStddev"),
        blackPixelRatio: numberFromRecord(snapshot, "blackPixelRatio"),
        saturationPixelRatio: numberFromRecord(snapshot, "saturationPixelRatio"),
        abnormalRowCount: numberFromRecord(snapshot, "abnormalRowCount"),
        abnormalColumnCount: numberFromRecord(snapshot, "abnormalColumnCount"),
        badPixelCount: numberFromRecord(snapshot, "badPixelCount"),
        qualitySummaryMessage: stringFromRecord(snapshot, "summaryMessage"),
        qualityDetails: objectFromRecord(snapshot, "details"),
    };
};

const buildProcessedQualitySource = (frame: ImageFrameRecord | null): QualityMetricSource | null => {
    const snapshot = frame?.processedQualitySnapshot;
    const qualityStatus = stringFromRecord(snapshot, "qualityStatus") ?? frame?.processedQualityStatus ?? null;
    if (!frame || !qualityStatus) {
        return null;
    }
    return {
        qualityStatus,
        pixelMin: numberFromRecord(snapshot, "pixelMin") ?? frame.processedPixelMin,
        pixelMax: numberFromRecord(snapshot, "pixelMax") ?? frame.processedPixelMax,
        pixelMean: numberFromRecord(snapshot, "pixelMean") ?? frame.processedPixelMean,
        pixelStddev: numberFromRecord(snapshot, "pixelStddev") ?? frame.processedPixelStddev,
        blackPixelRatio: numberFromRecord(snapshot, "blackPixelRatio") ?? frame.processedBlackPixelRatio,
        saturationPixelRatio: numberFromRecord(snapshot, "saturationPixelRatio") ?? frame.processedSaturationPixelRatio,
        abnormalRowCount: numberFromRecord(snapshot, "abnormalRowCount") ?? frame.processedAbnormalRowCount,
        abnormalColumnCount: numberFromRecord(snapshot, "abnormalColumnCount") ?? frame.processedAbnormalColumnCount,
        badPixelCount: numberFromRecord(snapshot, "badPixelCount") ?? frame.processedBadPixelCount,
        qualitySummaryMessage: stringFromRecord(snapshot, "summaryMessage") ?? frame.processedQualitySummaryMessage,
        qualityDetails: objectFromRecord(snapshot, "details") ?? frame.processedQualityDetails,
    };
};

const SpectralDataPage: React.FC = () => {
    const [loadingAction, setLoadingAction] = useState<string | null>(null);
    const [configHexText, setConfigHexText] = useState("");
    const [qualityViewMode, setQualityViewMode] = useState<QualityViewMode>("before");
    const [globalCalibrationSettings, setGlobalCalibrationSettings] =
        useState<CalibrationGlobalSettingsRecord | null>(null);
    const [spectrumAxis, setSpectrumAxis] = useState<"AUTO" | "X" | "Y">("AUTO");
    const [spectrumIntegrationMethod, setSpectrumIntegrationMethod] = useState<"MEAN" | "SUM">("MEAN");
    const [spectrumRectifyTilt, setSpectrumRectifyTilt] = useState(true);
    const [spectrumMaxShiftPixels, setSpectrumMaxShiftPixels] = useState<number | null>(null);
    const [spectrumRoi, setSpectrumRoi] = useState<Partial<SpectrumRoi>>({});
    const [spectrumResult, setSpectrumResult] = useState<SpectrumExtractionRecord | null>(null);
    const {
        connectionForm,
        bridgeState,
        websocketConnected,
        error,
        currentImage,
        latestStatus,
        latestConfigAck,
        configBytes,
        imageHistory,
        autoProcessAfterCapture,
        actions,
    } = useJNIStore();

    const connected = bridgeState.connected;

    useEffect(() => {
        jniBridgeService.initialize().catch((err: Error) => {
            actions.setError(err.message);
        });
    }, [actions]);

    useEffect(() => {
        let cancelled = false;
        jniBridgeService
            .getCalibrationGlobalSettings()
            .then((settings) => {
                if (!cancelled) {
                    setGlobalCalibrationSettings(settings);
                }
            })
            .catch(() => undefined);
        return () => {
            cancelled = true;
        };
    }, []);

    useEffect(() => {
        setQualityViewMode(buildProcessedQualitySource(currentImage) ? "after" : "before");
    }, [currentImage]);

    useEffect(() => {
        setSpectrumResult(null);
        setSpectrumRoi({});
        setSpectrumAxis("AUTO");
        setSpectrumIntegrationMethod("MEAN");
        setSpectrumRectifyTilt(true);
        setSpectrumMaxShiftPixels(null);
        let cancelled = false;
        if (currentImage) {
            jniBridgeService
                .getLatestSpectrum(currentImage.id)
                .then((spectrum) => {
                    if (!cancelled) {
                        setSpectrumResult(spectrum);
                    }
                })
                .catch(() => {
                    if (!cancelled) {
                        setSpectrumResult(null);
                    }
                });
        }
        return () => {
            cancelled = true;
        };
    }, [currentImage]);

    const connectionSummary = useMemo(() => {
        if (!connected) {
            return "未连接";
        }
        return `${bridgeState.host}:${bridgeState.controlPort} / image:${bridgeState.imagePort}`;
    }, [bridgeState.controlPort, bridgeState.host, bridgeState.imagePort, connected]);

    const currentOriginalQualitySource = useMemo(
        () => buildOriginalQualitySource(currentImage),
        [currentImage],
    );
    const currentProcessedQualitySource = useMemo(
        () => buildProcessedQualitySource(currentImage),
        [currentImage],
    );
    const currentRawHardQualitySource = useMemo(
        () => buildSnapshotQualitySource(currentImage?.rawHardQualitySnapshot),
        [currentImage],
    );
    const currentCalibratedQualitySource = useMemo(
        () => buildSnapshotQualitySource(currentImage?.calibratedQualitySnapshot),
        [currentImage],
    );
    const currentFrameCalibration = useMemo(
        () => objectFromRecord(currentImage?.qualityDetails, "calibration"),
        [currentImage],
    );
    const captureGlobalCalibrationEnabled =
        booleanFromRecord(currentFrameCalibration, "calibrationPackageEnabled") ??
        booleanFromRecord(currentFrameCalibration, "globalCalibrationEnabled");
    const captureCalibrationApplied = booleanFromRecord(currentFrameCalibration, "calibrationApplied");
    const captureDefectMapEnabled = booleanFromRecord(currentFrameCalibration, "defectMapEnabled");
    const captureDefectMapApplied = booleanFromRecord(currentFrameCalibration, "defectMapApplied");
    const captureDarkCalibrationLabel = calibrationReferenceLabel(
        currentFrameCalibration,
        "darkCalibrationLabel",
        "darkSessionNumber",
        "darkCalibrationId",
        "D",
    );
    const captureFlatCalibrationLabel = calibrationReferenceLabel(
        currentFrameCalibration,
        "flatCalibrationLabel",
        "flatSessionNumber",
        "flatCalibrationId",
        "F",
    );
    const captureDarkCalibrationId = numberFromRecord(currentFrameCalibration, "darkCalibrationId");
    const captureFlatCalibrationId = numberFromRecord(currentFrameCalibration, "flatCalibrationId");
    const currentActiveQualitySource = qualityViewMode === "after" && currentProcessedQualitySource
        ? currentProcessedQualitySource
        : currentOriginalQualitySource;
    const qualityMetricExplanations = useMemo(
        () => buildQualityMetricExplanations(currentActiveQualitySource),
        [currentActiveQualitySource],
    );
    const problemQualityMetrics = useMemo(
        () => qualityMetricExplanations.filter((metric) => metric.status === "WARNING" || metric.status === "FAIL"),
        [qualityMetricExplanations],
    );
    const qualityDecisionReasons = useMemo(
        () => getQualityDecisionReasons(currentActiveQualitySource),
        [currentActiveQualitySource],
    );
    const rawHardMetricExplanations = useMemo(
        () => buildQualityMetricExplanations(currentRawHardQualitySource).filter((metric) =>
            ["dynamicRange", "blackPixelRatio", "saturationPixelRatio"].includes(metric.key),
        ),
        [currentRawHardQualitySource],
    );
    const currentProcessingDisabledReason = useMemo(
        () => getProcessingDisabledReason(currentImage),
        [currentImage],
    );
    const currentProcessingDisplay = useMemo(
        () => getProcessingDisplay(currentImage),
        [currentImage],
    );

    const runAction = async (actionKey: string, task: () => Promise<unknown>, successMessage: string) => {
        setLoadingAction(actionKey);
        actions.setError(null);
        try {
            await task();
            toast.success(successMessage);
        } catch (err: any) {
            const message = err?.message || "操作失败";
            actions.setError(message);
            toast.error(message);
        } finally {
            setLoadingAction(null);
        }
    };

    const handleConnect = () =>
        runAction(
            "connect",
            () => jniBridgeService.connect(),
            "连接成功",
        );

    const handleDisconnect = () =>
        runAction(
            "disconnect",
            () => jniBridgeService.disconnect(),
            "已断开连接",
        );

    const handleReset = () =>
        runAction(
            "reset",
            () => jniBridgeService.sendReset(),
            "复位命令已发送",
        );

    const handleTriggerOnce = () =>
        runAction(
            "trigger",
            () => jniBridgeService.triggerOnceAndWaitForFrame({ autoProcess: autoProcessAfterCapture }),
            autoProcessAfterCapture ? "已获取一帧图像，并已按策略尝试自动处理" : "已获取一帧图像",
        );

    const handleProcessCurrentImage = () => {
        if (!currentImage) {
            toast.warning("暂无图像可处理，请先获取一帧图片。");
            return;
        }
        if (currentProcessingDisabledReason) {
            toast.warning(currentProcessingDisabledReason);
            return;
        }
        return runAction(
            "process",
            () => jniBridgeService.processImage(currentImage.id),
            "当前图像处理完成",
        );
    };

    const handleExtractCurrentSpectrum = () => {
        if (!currentImage) {
            toast.warning("暂无图像可提取，请先获取一帧图片。");
            return;
        }
        const disabledReason = getSpectrumExtractionDisabledReason(currentImage);
        if (disabledReason) {
            toast.warning(disabledReason);
            return;
        }

        const request: SpectrumExtractionRequest = {
            sourceMode: "AUTO",
            wavelengthAxis: spectrumAxis,
            rectifyTilt: spectrumRectifyTilt,
            integrationMethod: spectrumIntegrationMethod,
            roi: sanitizeRoiDraft(spectrumRoi),
        };
        if (typeof spectrumMaxShiftPixels === "number") {
            request.maxShiftPixels = spectrumMaxShiftPixels;
        }

        return runAction(
            "spectrum",
            async () => {
                const result = await jniBridgeService.extractSpectrum(currentImage.id, request);
                setSpectrumResult(result);
            },
            "一维光谱提取完成",
        );
    };

    const handleQueryStatus = () =>
        runAction(
            "status",
            () => jniBridgeService.queryStatusAndWait(),
            "状态查询完成",
        );

    const handleSendFullConfig = () =>
        runAction(
            "config",
            () => jniBridgeService.sendFullConfigAndWait(configBytes),
            "完整配置已下发",
        );

    const handleParseHexConfig = () => {
        try {
            actions.replaceConfigBytes(parseLittleEndianHexBytes(configHexText));
            setConfigHexText(normalizeHexText(configHexText));
            actions.setError(null);
            toast.success("十六进制配置已解析到表格");
        } catch (err: any) {
            const message = err?.message || "十六进制配置解析失败";
            actions.setError(message);
            toast.error(message);
        }
    };

    const handleGenerateHexConfig = () => {
        setConfigHexText(toLittleEndianHexText(configBytes));
        toast.success("已从表格生成十六进制配置");
    };

    return (
        <div className="space-y-6 p-6">
            <Card className="bg-white border border-gray-200 shadow-sm">
                <div className="flex flex-wrap items-center justify-between gap-4">
                    <div className="flex items-center gap-3">
                        <div className="p-2.5 bg-blue-500 rounded-lg">
                            <Plug size={22} className="text-white" />
                        </div>
                        <div>
                            <div className="flex flex-wrap items-center gap-2">
                                <Title level={4} className="!mb-0 !text-slate-800">
                                    光谱桥接控制
                                </Title>
                                <Tag color={connected ? "green" : "default"} className="border-0">
                                    {connected ? "已连接" : "未连接"}
                                </Tag>
                                <Tag color={websocketConnected ? "blue" : "orange"} className="border-0">
                                    WS {websocketConnected ? "在线" : "离线"}
                                </Tag>
                            </div>
                            <Text className="text-slate-500 text-sm">{connectionSummary}</Text>
                        </div>
                    </div>

                    <div className="flex flex-wrap gap-2">
                        <Button
                            type="primary"
                            icon={<Plug size={16} />}
                            loading={loadingAction === "connect"}
                            disabled={loadingAction !== null || connected}
                            onClick={handleConnect}
                        >
                            连接
                        </Button>
                        <Button
                            danger
                            icon={<Unplug size={16} />}
                            loading={loadingAction === "disconnect"}
                            disabled={loadingAction !== null || !connected}
                            onClick={handleDisconnect}
                        >
                            断开
                        </Button>
                    </div>
                </div>

                {error && (
                    <Alert
                        message="操作失败"
                        description={error}
                        type="error"
                        icon={<AlertCircle size={16} />}
                        showIcon
                        closable
                        className="mt-5 rounded-md"
                        onClose={() => actions.setError(null)}
                    />
                )}

                <div className="mt-6 grid gap-4 lg:grid-cols-[1.2fr_1fr_1fr_auto]">
                    <div>
                        <Text className="mb-2 block text-sm text-slate-600">Host</Text>
                        <Input
                            value={connectionForm.host}
                            onChange={(event) => actions.setConnectionField("host", event.target.value)}
                            placeholder="例如 192.168.1.10"
                            disabled={connected}
                        />
                    </div>
                    <div>
                        <Text className="mb-2 block text-sm text-slate-600">Control Port</Text>
                        <InputNumber
                            min={1}
                            max={65535}
                            value={connectionForm.controlPort || null}
                            onChange={(value) => actions.setConnectionField("controlPort", Number(value || 0))}
                            className="w-full"
                            disabled={connected}
                        />
                    </div>
                    <div>
                        <Text className="mb-2 block text-sm text-slate-600">Image Port</Text>
                        <InputNumber
                            min={1}
                            max={65535}
                            value={connectionForm.imagePort || null}
                            onChange={(value) => actions.setConnectionField("imagePort", Number(value || 0))}
                            className="w-full"
                            disabled={connected}
                        />
                    </div>
                    <div className="flex items-end pb-1">
                        <Checkbox
                            checked={connectionForm.verifyCrc}
                            disabled={connected}
                            onChange={(event) => actions.setConnectionField("verifyCrc", event.target.checked)}
                        >
                            CRC
                        </Checkbox>
                    </div>
                </div>
            </Card>

            <Card className="bg-white border border-gray-200 shadow-sm">
                <div className="flex flex-col">
                    <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
                        <Title level={5} className="!mb-0 flex items-center gap-2 !text-slate-800">
                            <Activity size={18} />
                            命令与原始状态
                        </Title>
                        <div className="flex flex-wrap gap-2">
                            <Button
                                icon={<RotateCcw size={16} />}
                                loading={loadingAction === "reset"}
                                disabled={!connected || loadingAction !== null}
                                onClick={handleReset}
                            >
                                复位
                            </Button>
                            <Button
                                icon={<Binary size={16} />}
                                loading={loadingAction === "status"}
                                disabled={!connected || loadingAction !== null}
                                onClick={handleQueryStatus}
                            >
                                查询状态
                            </Button>
                        </div>
                    </div>

                    <div className="grid gap-3 lg:grid-cols-[minmax(0,1.2fr)_minmax(260px,0.8fr)_minmax(220px,0.6fr)]">
                        <div className="rounded-lg border border-slate-200 bg-slate-50 p-4">
                            <Text className="mb-2 block text-sm text-slate-500">状态位</Text>
                            <div className="max-h-24 overflow-auto break-all rounded bg-white p-3 font-mono text-xs leading-relaxed text-slate-800">
                                {latestStatus?.statusBinary || "暂无状态数据"}
                            </div>
                            {latestStatus && (
                                <div className="mt-2 text-xs text-slate-500">
                                    statusBits={latestStatus.statusBits} · errorCode={latestStatus.errorCode}
                                </div>
                            )}
                        </div>

                        <div className="rounded-lg border border-slate-200 bg-slate-50 p-4">
                            <Text className="mb-2 block text-sm text-slate-500">配置应答</Text>
                            {latestConfigAck ? (
                                <div className="font-mono text-sm text-slate-800">
                                    resultCode={latestConfigAck.resultCode} · failedAddr=
                                    {latestConfigAck.failedAddr}
                                </div>
                            ) : (
                                <Text className="text-slate-500">暂无配置应答</Text>
                            )}
                        </div>

                        <div className="rounded-lg border border-slate-200 bg-slate-50 p-4">
                            <Text className="mb-2 block text-sm text-slate-500">数据库历史帧</Text>
                            <div className="flex items-center gap-2">
                                <Tag color="green">PostgreSQL</Tag>
                                <Text className="text-sm text-slate-700">{imageHistory.length} 帧</Text>
                            </div>
                        </div>
                    </div>
                </div>
            </Card>

            <Card className="bg-white border border-gray-200 shadow-sm">
                    <div className="flex h-full flex-col">
                        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
                            <div>
                                <Title level={5} className="!mb-0 flex items-center gap-2 !text-slate-800">
                                    <Camera size={18} />
                                    当前图像帧
                                </Title>
                                <Text className="text-xs text-slate-500">显示最新一帧预览，原始 RAW 已保存在服务器</Text>
                            </div>
                            <div className="flex flex-wrap items-center justify-end gap-2">
                                <Tag color={globalCalibrationSettings?.enabled ? "cyan" : "default"}>
                                    {globalCalibrationSettings?.enabled
                                        ? globalCalibrationSettings.defectMapEnabled
                                            ? "校准包 + 稳定缺陷修复已启用"
                                            : "校准包已启用"
                                        : "校准包未启用"}
                                </Tag>
                                <Checkbox
                                    checked={autoProcessAfterCapture}
                                    disabled={loadingAction !== null}
                                    onChange={(event) => actions.setAutoProcessAfterCapture(event.target.checked)}
                                >
                                    获取后自动处理
                                </Checkbox>
                                <Button
                                    type="primary"
                                    icon={<Camera size={16} />}
                                    loading={loadingAction === "trigger"}
                                    disabled={!connected || loadingAction !== null}
                                    onClick={handleTriggerOnce}
                                >
                                    获取一帧
                                </Button>
                                <Button
                                    loading={loadingAction === "process"}
                                    disabled={
                                        !currentImage ||
                                        Boolean(currentProcessingDisabledReason) ||
                                        loadingAction !== null
                                    }
                                    onClick={handleProcessCurrentImage}
                                >
                                    {getProcessingButtonLabel(currentImage, "处理当前帧")}
                                </Button>
                            </div>
                        </div>

                        <div className="grid gap-4 xl:grid-cols-[minmax(50%,1.1fr)_minmax(320px,0.9fr)] xl:items-start">
                            <div className="min-h-[320px] overflow-hidden rounded-lg border border-slate-200 bg-slate-950 shadow-inner">
                                {currentImage?.imageDataUrl ? (
                                    <div
                                        className={`grid h-full min-h-[320px] gap-px bg-slate-800 ${
                                            currentImage.processedImageDataUrl ? "md:grid-cols-2" : "grid-cols-1"
                                        }`}
                                    >
                                        <div className="flex min-h-[320px] flex-col bg-slate-950">
                                            <div className="flex flex-1 items-center justify-center p-2">
                                                <img
                                                    src={currentImage.imageDataUrl}
                                                    alt="当前原始光谱图像"
                                                    className="max-h-[500px] max-w-full object-contain"
                                                />
                                            </div>
                                            <div className="border-t border-slate-800 px-3 py-2 text-center text-xs font-medium text-slate-300">
                                                原图
                                            </div>
                                        </div>
                                        {currentImage.processedImageDataUrl && (
                                            <div className="flex min-h-[320px] flex-col bg-slate-950">
                                                <div className="flex flex-1 items-center justify-center p-2">
                                                    <img
                                                        src={currentImage.processedImageDataUrl}
                                                        alt="当前处理后光谱图像"
                                                        className="max-h-[500px] max-w-full object-contain"
                                                    />
                                                </div>
                                                <div className="border-t border-slate-800 px-3 py-2 text-center text-xs font-medium text-emerald-300">
                                                    处理后
                                                </div>
                                            </div>
                                        )}
                                    </div>
                                ) : (
                                    <div className="flex min-h-[320px] flex-col items-center justify-center text-center text-slate-400">
                                        <Camera size={42} className="mx-auto mb-3 opacity-60" />
                                        <Text className="text-slate-400">暂无图像帧</Text>
                                    </div>
                                )}
                            </div>

                            <div className="space-y-3">
                                {!currentImage && (
                                    <div className="rounded-lg border border-slate-200 bg-slate-50 p-4 text-sm text-slate-500">
                                        获取一帧后，这里会显示尺寸、质量、处理状态以及本帧校准审计。
                                    </div>
                                )}

                                {currentImage && (
                                    <div className="grid gap-2 rounded-lg border border-slate-200 bg-slate-50 p-3 text-sm text-slate-600 sm:grid-cols-2 xl:grid-cols-1 2xl:grid-cols-2">
                                        <div>
                                            <Text className="block text-xs text-slate-500">尺寸</Text>
                                            <span className="font-medium text-slate-800">
                                                {currentImage.width} x {currentImage.height}
                                            </span>
                                        </div>
                                        <div>
                                            <Text className="block text-xs text-slate-500">8-bit预览</Text>
                                            <span className="font-medium text-slate-800">{currentImage.raw8Length} bytes</span>
                                        </div>
                                        <div>
                                            <Text className="block text-xs text-slate-500">接收时间</Text>
                                            <span className="font-medium text-slate-800">
                                                {new Date(currentImage.timestamp).toLocaleString("zh-CN")}
                                            </span>
                                        </div>
                                        <div>
                                            <Text className="block text-xs text-slate-500">质量状态</Text>
                                            <Tag color={qualityColor(currentImage.qualityStatus)} className="mt-1">
                                                {currentImage.qualityStatus || "NOT_EVALUATED"}
                                            </Tag>
                                        </div>
                                        <div>
                                            <Text className="block text-xs text-slate-500">图像处理</Text>
                                            <Tag color={currentProcessingDisplay.color} className="mt-1">
                                                {currentProcessingDisplay.label}
                                            </Tag>
                                        </div>
                                        <div className="sm:col-span-2 xl:col-span-1 2xl:col-span-2">
                                            <Text className="mb-1 block text-xs text-slate-500">RAW16像素</Text>
                                            <ImagePixelDataViewer
                                                frame={currentImage}
                                                defaultSource={
                                                    qualityViewMode === "after" && currentProcessedQualitySource
                                                        ? "PROCESSED"
                                                        : "ORIGINAL"
                                                }
                                            />
                                        </div>
                                    </div>
                                )}

                                {currentImage && captureGlobalCalibrationEnabled && (
                                    <div className="rounded-lg border border-cyan-200 bg-cyan-50 p-3">
                                <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
                                    <Text className="text-sm font-medium text-cyan-800">本帧校准包与缺陷地图审计</Text>
                                    <div className="flex flex-wrap gap-2">
                                        <Tag color="cyan" className="m-0">采集时校准包已锁定</Tag>
                                        <Tag color={captureCalibrationApplied ? "green" : "orange"} className="m-0">
                                            {captureCalibrationApplied ? "已应用匹配参考" : "未找到匹配参考"}
                                        </Tag>
                                        <Tag color={captureDefectMapApplied ? "green" : captureDefectMapEnabled ? "orange" : "default"} className="m-0">
                                            {captureDefectMapApplied
                                                ? "已校正稳定缺陷"
                                                : captureDefectMapEnabled
                                                  ? "稳定缺陷地图无可校正项"
                                                  : "未启用稳定缺陷修复"}
                                        </Tag>
                                    </div>
                                </div>
                                <Text className="block text-xs leading-5 text-slate-600">
                                    {stringFromRecord(currentFrameCalibration, "message") ||
                                        "原始 RAW 不会被覆盖；先保留硬性检查，再对校准后数据做完整质量分析。"}
                                </Text>
                                <div className="mt-3 grid gap-2 sm:grid-cols-2">
                                    <div className="rounded-md border border-cyan-100 bg-white/80 p-2.5 text-xs">
                                        <div className="mb-1 text-slate-500">采集锁定暗场</div>
                                        <div className="flex flex-wrap items-center gap-2">
                                            <Tag color="blue" className="m-0">{captureDarkCalibrationLabel}</Tag>
                                            {captureDarkCalibrationId !== null && (
                                                <span className="text-slate-500">数据库ID {captureDarkCalibrationId}</span>
                                            )}
                                        </div>
                                    </div>
                                    <div className="rounded-md border border-cyan-100 bg-white/80 p-2.5 text-xs">
                                        <div className="mb-1 text-slate-500">采集锁定平场</div>
                                        <div className="flex flex-wrap items-center gap-2">
                                            <Tag color="purple" className="m-0">{captureFlatCalibrationLabel}</Tag>
                                            {captureFlatCalibrationId !== null && (
                                                <span className="text-slate-500">数据库ID {captureFlatCalibrationId}</span>
                                            )}
                                        </div>
                                    </div>
                                </div>
                                {currentRawHardQualitySource && (
                                    <div className="mt-3">
                                        <div className="mb-2 flex flex-wrap items-center gap-2">
                                            <Text className="text-xs font-medium text-slate-700">原始 RAW 硬性检查</Text>
                                            <Tag color={qualityColor(currentRawHardQualitySource.qualityStatus)} className="m-0">
                                                {currentRawHardQualitySource.qualityStatus}
                                            </Tag>
                                        </div>
                                        <div className="grid gap-2 md:grid-cols-3">
                                            {rawHardMetricExplanations.map((metric) => (
                                                <div key={metric.key} className="rounded-md border border-cyan-100 bg-white/80 p-2.5 text-xs">
                                                    <div className="flex items-center justify-between gap-2">
                                                        <span className="font-medium text-slate-700">{metric.label}</span>
                                                        <Tag color={metricColor(metric.status)} className="m-0">{metric.status}</Tag>
                                                    </div>
                                                    <div className="mt-1 font-mono text-slate-800">{metric.value}</div>
                                                    <div className="mt-1 text-slate-500">合格：{metric.healthyCondition}</div>
                                                </div>
                                            ))}
                                        </div>
                                    </div>
                                )}
                                        <Text className="mt-3 block text-xs text-slate-600">
                                            {currentCalibratedQualitySource
                                                ? `本帧完整质量分析基于校准后数据，状态：${currentCalibratedQualitySource.qualityStatus}。`
                                                : "本帧没有可实际应用的尺寸匹配参考，仍展示原始 RAW 的完整质量分析。"}
                                        </Text>
                                    </div>
                                )}
                            </div>
                        </div>
                    </div>
                </Card>

            <Card className="bg-white border border-gray-200 shadow-sm">
                <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
                    <div>
                        <Title level={5} className="!mb-0 flex items-center gap-2 !text-slate-800">
                            <Activity size={18} />
                            当前帧质量诊断
                        </Title>
                        <Text className="text-xs text-slate-500">
                            展开为横向指标面板，便于快速定位导致图片不合格的原因
                        </Text>
                    </div>
                    <Tag color={qualityColor(currentActiveQualitySource?.qualityStatus)}>
                        {currentActiveQualitySource?.qualityStatus || "NOT_EVALUATED"}
                    </Tag>
                </div>

                {!currentImage ? (
                    <Text className="text-sm text-slate-500">暂无图像，获取一帧后显示质量诊断。</Text>
                ) : (
                    <div className="space-y-4">
                        <div className="flex flex-wrap gap-2">
                            <Button
                                type={qualityViewMode === "before" ? "primary" : "default"}
                                onClick={() => setQualityViewMode("before")}
                            >
                                处理前
                            </Button>
                            <Button
                                type={qualityViewMode === "after" ? "primary" : "default"}
                                disabled={!currentProcessedQualitySource}
                                onClick={() => setQualityViewMode("after")}
                            >
                                处理后
                            </Button>
                        </div>

                        {qualityViewMode === "before" && (
                        <div className="rounded-lg border border-blue-200 bg-blue-50 p-3">
                            <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
                                <Text className="text-sm font-medium text-blue-700">质量处置建议</Text>
                                <div className="flex flex-wrap gap-2">
                                    <Tag color={dispositionColor(currentImage.dispositionStatus)} className="m-0">
                                        {currentImage.dispositionStatus || "MANUAL_REVIEW"}
                                    </Tag>
                                    <Tag color={currentImage.usableForSpectral ? "green" : "orange"} className="m-0">
                                        {currentImage.usableForSpectral ? "可进入光谱提取" : "暂不进入光谱提取"}
                                    </Tag>
                                </div>
                            </div>
                            <div className="text-sm leading-relaxed text-slate-600">
                                {currentImage.dispositionMessage || "暂无处置建议，请先完成质量分析。"}
                            </div>
                            {currentProcessingDisabledReason && (
                                <div className="mt-2 rounded-md border border-orange-200 bg-orange-50 px-3 py-2 text-xs leading-relaxed text-orange-700">
                                    {autoProcessAfterCapture ? "自动处理提示" : "处理限制"}：
                                    {currentProcessingDisabledReason}
                                </div>
                            )}
                            {currentImage.recommendedActions.length > 0 && (
                                <div className="mt-3 grid gap-2 md:grid-cols-2 xl:grid-cols-3">
                                    {currentImage.recommendedActions.map((action) => (
                                        <div key={action.code} className="rounded-md bg-white/80 p-2.5 text-xs">
                                            <div className="mb-1 flex flex-wrap items-center gap-2">
                                                <Tag color={actionColor(action.severity)} className="m-0">
                                                    {action.stage}
                                                </Tag>
                                                <span className="font-medium text-slate-800">{action.label}</span>
                                            </div>
                                            <div className="leading-relaxed text-slate-500">{action.reason}</div>
                                        </div>
                                ))}
                            </div>
                        )}
                        </div>
                        )}

                        {qualityViewMode === "after" && currentImage.processingStatus && (
                            <div className="rounded-lg border border-emerald-200 bg-emerald-50 p-3">
                                <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
                                    <Text className="text-sm font-medium text-emerald-700">处理后复检结果</Text>
                                    <div className="flex flex-wrap gap-2">
                                        <Tag color={processingColor(currentImage.processingStatus)} className="m-0">
                                            {processingLabel(currentImage.processingStatus)}
                                        </Tag>
                                        {currentImage.processedQualityStatus && (
                                            <Tag color={qualityColor(currentImage.processedQualityStatus)} className="m-0">
                                                复检 {currentImage.processedQualityStatus}
                                            </Tag>
                                        )}
                                        {typeof currentImage.processedUsableForSpectral === "boolean" && (
                                            <Tag
                                                color={currentImage.processedUsableForSpectral ? "green" : "orange"}
                                                className="m-0"
                                            >
                                                {currentImage.processedUsableForSpectral
                                                    ? "处理后可进入光谱提取"
                                                    : "处理后仍需复核"}
                                            </Tag>
                                        )}
                                        {currentImage.processedDispositionStatus && (
                                            <Tag
                                                color={dispositionColor(currentImage.processedDispositionStatus)}
                                                className="m-0"
                                            >
                                                处置 {currentImage.processedDispositionStatus}
                                            </Tag>
                                        )}
                                    </div>
                                </div>
                                <div className="text-sm leading-relaxed text-slate-600">
                                    {currentImage.processedDispositionMessage ||
                                        currentImage.processingMessage ||
                                        "已完成当前阶段可执行的图像处理。"}
                                </div>
                                {currentImage.executedProcessingActions.length > 0 && (
                                    <div className="mt-3 grid gap-2 md:grid-cols-2 xl:grid-cols-3">
                                        {currentImage.executedProcessingActions.map((action) => (
                                            <div
                                                key={String(action.code)}
                                                className="rounded-md bg-white/80 p-2.5 text-xs"
                                            >
                                                <div className="font-medium text-slate-800">
                                                    {String(action.label || action.code)}
                                                </div>
                                                <div className="mt-1 text-slate-500">
                                                    修正数量：{String(action.correctedCount ?? "-")}
                                                </div>
                                                <div className="mt-1 leading-relaxed text-slate-500">
                                                    {String(action.method || "")}
                                                </div>
                                            </div>
                                        ))}
                                    </div>
                                )}
                            </div>
                        )}

                        {currentActiveQualitySource?.qualityStatus !== "PASS" && (
                            <div className="rounded-lg border border-orange-200 bg-orange-50 p-3">
                                <Text className="block text-sm font-medium text-orange-700">
                                    导致当前{qualityViewMode === "after" ? "复检" : ""}状态不是 PASS 的指标
                                </Text>
                                {problemQualityMetrics.length > 0 ? (
                                    <div className="mt-3 grid gap-3 md:grid-cols-2 xl:grid-cols-3">
                                        {problemQualityMetrics.map((metric) => (
                                            <div key={metric.key} className="rounded-md bg-white/80 p-3 text-sm">
                                                <div className="mb-1 flex items-center gap-2">
                                                    <Tag color={metricColor(metric.status)} className="m-0">
                                                        {metric.status}
                                                    </Tag>
                                                    <span className="font-medium text-slate-800">{metric.label}</span>
                                                </div>
                                                <div className="font-mono text-slate-700">当前值 {metric.value}</div>
                                                <div className="mt-1 text-xs leading-relaxed text-slate-500">
                                                    {metric.unhealthyReason}
                                                </div>
                                            </div>
                                        ))}
                                    </div>
                                ) : (
                                    <div className="mt-2 text-sm text-slate-600">
                                        {currentActiveQualitySource?.qualitySummaryMessage ||
                                            qualityDecisionReasons.join("；") ||
                                            "后端返回了非PASS状态，但没有提供具体原因。"}
                                    </div>
                                )}
                            </div>
                        )}

                        <div>
                            <Text className="mb-2 block text-sm font-medium text-slate-700">
                                {qualityViewMode === "before" && currentCalibratedQualitySource
                                    ? captureDefectMapApplied
                                        ? "校准及缺陷地图修复后完整质量指标"
                                        : "校准后完整质量指标"
                                    : qualityViewMode === "before"
                                      ? "原始 RAW 完整质量指标"
                                      : "处理后完整质量指标"}
                            </Text>
                            <div className="grid gap-3 lg:grid-cols-2 2xl:grid-cols-3">
                            {qualityMetricExplanations.map((metric) => (
                                <div key={metric.key} className="rounded-lg border border-slate-200 bg-slate-50 p-3">
                                    <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
                                        <div className="flex items-center gap-2">
                                            <Tag color={metricColor(metric.status)} className="m-0">
                                                {metric.status}
                                            </Tag>
                                            <span className="font-medium text-slate-800">{metric.label}</span>
                                        </div>
                                        <span className="font-mono text-sm text-slate-700">{metric.value}</span>
                                    </div>
                                    <div className="space-y-1 text-xs leading-relaxed text-slate-500">
                                        <div>
                                            <span className="font-medium text-slate-600">合格条件：</span>
                                            {metric.healthyCondition}
                                        </div>
                                        <div>
                                            <span className="font-medium text-slate-600">异常原因：</span>
                                            {metric.unhealthyReason}
                                        </div>
                                        <div>
                                            <span className="font-medium text-slate-600">参考依据：</span>
                                            {metric.reference}
                                        </div>
                                    </div>
                                </div>
                            ))}
                            </div>
                        </div>
                    </div>
                )}
            </Card>

            <Card className="bg-white border border-gray-200 shadow-sm">
                <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
                    <div>
                        <Title level={5} className="!mb-0 flex items-center gap-2 !text-slate-800">
                            <Activity size={18} />
                            当前帧一维光谱提取
                        </Title>
                        <Text className="text-xs text-slate-500">
                            同一张图像只保留最新一条像素域光谱；改变参数后重新提取会覆盖原记录
                        </Text>
                    </div>
                    <Tag color={canExtractSpectrum(currentImage) ? "green" : "orange"}>
                        {canExtractSpectrum(currentImage) ? "可提取" : "等待PASS图像"}
                    </Tag>
                </div>

                {!currentImage ? (
                    <Text className="text-sm text-slate-500">暂无图像，获取一帧后可提取一维光谱。</Text>
                ) : (
                    <div className="space-y-4">
                        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
                            <div>
                                <Text className="mb-1 block text-xs text-slate-500">波长方向</Text>
                                <Select
                                    value={spectrumAxis}
                                    className="w-full"
                                    options={[
                                        { value: "AUTO", label: "自动判断" },
                                        { value: "X", label: "X 横向" },
                                        { value: "Y", label: "Y 纵向" },
                                    ]}
                                    onChange={(value) => setSpectrumAxis(value as "AUTO" | "X" | "Y")}
                                />
                            </div>
                            <div>
                                <Text className="mb-1 block text-xs text-slate-500">积分方式</Text>
                                <Select
                                    value={spectrumIntegrationMethod}
                                    className="w-full"
                                    options={[
                                        { value: "MEAN", label: "平均强度" },
                                        { value: "SUM", label: "积分总强度" },
                                    ]}
                                    onChange={(value) => setSpectrumIntegrationMethod(value as "MEAN" | "SUM")}
                                />
                            </div>
                            <div>
                                <Text className="mb-1 block text-xs text-slate-500">最大矫正偏移</Text>
                                <InputNumber
                                    value={spectrumMaxShiftPixels}
                                    min={0}
                                    max={200}
                                    className="w-full"
                                    placeholder="自动"
                                    addonAfter="px"
                                    onChange={(value) =>
                                        setSpectrumMaxShiftPixels(typeof value === "number" ? value : null)
                                    }
                                />
                            </div>
                        </div>

                        <div className="grid gap-3 md:grid-cols-4">
                            {(["xStart", "xEnd", "yStart", "yEnd"] as const).map((key) => (
                                <div key={key}>
                                    <Text className="mb-1 block text-xs text-slate-500">{key}</Text>
                                    <InputNumber
                                        value={spectrumRoi[key]}
                                        min={0}
                                        max={key.startsWith("x") ? currentImage.width : currentImage.height}
                                        className="w-full"
                                        placeholder={
                                            key === "xStart"
                                                ? "0"
                                                : key === "xEnd"
                                                  ? String(currentImage.width)
                                                  : key === "yStart"
                                                    ? "0"
                                                    : String(currentImage.height)
                                        }
                                        onChange={(value) =>
                                            setSpectrumRoi((state) => ({
                                                ...state,
                                                [key]: typeof value === "number" ? value : undefined,
                                            }))
                                        }
                                    />
                                </div>
                            ))}
                        </div>

                        <div className="flex flex-wrap items-center justify-between gap-3">
                            <Checkbox
                                checked={spectrumRectifyTilt}
                                onChange={(event) => setSpectrumRectifyTilt(event.target.checked)}
                            >
                                提取前进行轻微倾斜矫正
                            </Checkbox>
                            <Button
                                type="primary"
                                loading={loadingAction === "spectrum"}
                                disabled={
                                    Boolean(getSpectrumExtractionDisabledReason(currentImage)) ||
                                    loadingAction !== null
                                }
                                title={getSpectrumExtractionDisabledReason(currentImage) || "提取一维像素域光谱"}
                                onClick={handleExtractCurrentSpectrum}
                            >
                                提取一维光谱
                            </Button>
                        </div>

                        {getSpectrumExtractionDisabledReason(currentImage) && (
                            <div className="rounded-md border border-orange-200 bg-orange-50 px-3 py-2 text-xs leading-relaxed text-orange-700">
                                {getSpectrumExtractionDisabledReason(currentImage)}
                            </div>
                        )}

                        {spectrumResult && (
                            <div className="space-y-3 rounded-lg border border-cyan-100 bg-cyan-50 p-3">
                                <div className="flex flex-wrap items-center gap-2 text-xs text-slate-600">
                                    <Tag color="blue" className="m-0">
                                        来源 {spectrumResult.sourceMode}
                                    </Tag>
                                    <Tag color="cyan" className="m-0">
                                        方向 {spectrumResult.wavelengthAxis}
                                    </Tag>
                                    <Tag color="purple" className="m-0">
                                        点数 {spectrumResult.pointCount}
                                    </Tag>
                                    <Tag color="geekblue" className="m-0">
                                        偏移 {spectrumResult.shiftMin}~{spectrumResult.shiftMax}px
                                    </Tag>
                                </div>
                                <SpectrumCurve spectrum={spectrumResult} />
                                <div className="grid gap-2 text-xs text-slate-600 sm:grid-cols-3">
                                    <div>最小强度：{formatNullableNumber(spectrumResult.intensityMin)}</div>
                                    <div>最大强度：{formatNullableNumber(spectrumResult.intensityMax)}</div>
                                    <div>平均强度：{formatNullableNumber(spectrumResult.intensityMean)}</div>
                                </div>
                                <div className="text-xs leading-relaxed text-slate-500">
                                    {spectrumResult.summaryMessage}
                                </div>
                            </div>
                        )}
                    </div>
                )}
            </Card>

            <Card className="bg-white border border-gray-200 shadow-sm">
                <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
                    <Title level={5} className="!mb-0 flex items-center gap-2 !text-slate-800">
                        <Settings size={18} />
                        FPGA 完整配置
                    </Title>
                    <div className="flex flex-wrap gap-2">
                        <Button onClick={() => actions.resetConfigBytes()} disabled={loadingAction !== null}>
                            清零
                        </Button>
                        <Button
                            type="primary"
                            icon={<Send size={16} />}
                            loading={loadingAction === "config"}
                            disabled={!connected || loadingAction !== null}
                            onClick={handleSendFullConfig}
                        >
                            发送 512 字节
                        </Button>
                    </div>
                </div>

                <div className="mb-4 rounded-md border border-slate-200 bg-slate-50 p-4">
                    <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
                        <div>
                            <Text className="block text-sm font-medium text-slate-700">十六进制配置输入</Text>
                            <Text className="text-xs text-slate-500">
                                共 512 字节，输入 1024 个十六进制字符；最右侧低位字节解析为 #0。
                            </Text>
                        </div>
                        <div className="flex flex-wrap gap-2">
                            <Button onClick={handleParseHexConfig} disabled={loadingAction !== null}>
                                解析到表格
                            </Button>
                            <Button onClick={handleGenerateHexConfig} disabled={loadingAction !== null}>
                                从表格生成
                            </Button>
                            <Button onClick={() => setConfigHexText("")} disabled={loadingAction !== null}>
                                清空输入
                            </Button>
                        </div>
                    </div>
                    <Input.TextArea
                        value={configHexText}
                        onChange={(event) => setConfigHexText(event.target.value)}
                        autoSize={{ minRows: 3, maxRows: 8 }}
                        spellCheck={false}
                        className="font-mono"
                        placeholder="例如：0A12...，其中 12 会解析为 #0，0A 会解析为 #1"
                    />
                    <div className="mt-2 text-xs text-slate-500">
                        已识别 {normalizeHexText(configHexText).length} / {CONFIG_HEX_LENGTH} 个十六进制字符
                    </div>
                </div>

                <div className="max-h-[420px] overflow-auto rounded-md border border-slate-200 p-3">
                    <div className="grid grid-cols-2 gap-2 sm:grid-cols-4 md:grid-cols-8 xl:grid-cols-16">
                        {byteIndexes.map((index) => (
                            <label key={index} className="block">
                                <span className="mb-1 block text-[11px] text-slate-500">#{index}</span>
                                <InputNumber
                                    min={0}
                                    max={255}
                                    size="small"
                                    value={configBytes[index]}
                                    onChange={(value) => actions.setConfigByte(index, Number(value || 0))}
                                    className="w-full"
                                    disabled={loadingAction === "config"}
                                />
                            </label>
                        ))}
                    </div>
                </div>

                {latestConfigAck?.resultCode === 0 && (
                    <Alert
                        className="mt-4 rounded-md"
                        type="success"
                        showIcon
                        icon={<CheckCircle size={16} />}
                        message="配置已确认"
                        description={`failedAddr=${latestConfigAck.failedAddr}`}
                    />
                )}
            </Card>
        </div>
    );
};

export default SpectralDataPage;
