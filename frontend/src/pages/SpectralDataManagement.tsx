import React, { useEffect, useState } from "react";
import { Button, Card, Checkbox, Empty, InputNumber, Modal, Select, Segmented, Space, Spin, Table, Tag, Typography } from "antd";
import type { ColumnsType } from "antd/es/table";
import { Activity, Clock, Database, Eye, Image as ImageIcon, RefreshCw, Trash2 } from "lucide-react";
import { toast } from "sonner";

import { jniBridgeService } from "../service/jniBridgeService";
import FpgaPayloadDataViewer from "../components/FpgaPayloadDataViewer";
import ImagePixelDataViewer from "../components/ImagePixelDataViewer";
import ImageVersionPreview from "../components/ImageVersionPreview";
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
type HistoryScene = "NORMAL" | "HDR";

type SpectralDataManagementPageProps = {
    scene?: HistoryScene;
};

type HdrPlanePreviewItem = {
    key: string;
    label: string;
    url: string;
    tagColor: string;
    description: string;
};

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

const isRowMajorFrame = (frame?: ImageFrameRecord | null): boolean =>
    String(frame?.readoutOrder ?? "").toUpperCase() === "ROW_MAJOR";

const isHdrFrame = (frame?: ImageFrameRecord | null): boolean =>
    String(frame?.captureScene ?? "").toUpperCase() === "HDR";

const shouldShowFpgaPayloadMapping = (frame?: ImageFrameRecord | null): boolean =>
    Boolean(frame?.fpgaPayloadStorageUri) && (isHdrFrame(frame) || !isRowMajorFrame(frame));

const fpgaPayloadMappingTriggerLabel = (frame?: ImageFrameRecord | null): string =>
    isHdrFrame(frame) ? "查看原始HDR payload映射" : "查看原始FPGA payload映射";

const originalImageSourceLabel = (frame?: ImageFrameRecord | null): string =>
    isRowMajorFrame(frame) ? "正常行列原图" : "重排后原图";

const spectrumSourceLabel = (sourceMode?: string | null, frame?: ImageFrameRecord | null): string => {
    if (sourceMode === "PROCESSED") {
        return "处理后图像";
    }
    if (sourceMode === "CALIBRATED") {
        return "校准后图像";
    }
    if (sourceMode === "ORIGINAL") {
        return originalImageSourceLabel(frame);
    }
    return sourceMode || "未知来源";
};

const dispositionStatusLabel = (status?: string | null): string => {
    if (status === "USE_AS_IS") {
        return "直接使用";
    }
    if (status === "PROCESS_REQUIRED") {
        return "需要处理";
    }
    if (status === "MANUAL_REVIEW") {
        return "人工复核";
    }
    if (status === "RECAPTURE_RECOMMENDED" || status === "RECAPTURE_RECOMMEN") {
        return "建议重采";
    }
    if (status === "REJECTED") {
        return "拒绝使用";
    }
    return status || "人工复核";
};

const buildHdrPlanePreviewItems = (frame: ImageFrameRecord | null): HdrPlanePreviewItem[] => {
    if (!frame || frame.captureScene !== "HDR") {
        return [];
    }
    return [
        {
            key: "hg",
            label: "HG 高增益平面",
            url: frame.hgImageDataUrl,
            tagColor: "geekblue",
            description: "一次HDR触发中的高增益输入平面，用于保留弱信号细节；它不直接替代融合主图进入普通质量处理流程。",
        },
        {
            key: "lg",
            label: "LG 低增益平面",
            url: frame.lgImageDataUrl,
            tagColor: "purple",
            description: "一次HDR触发中的低增益输入平面，用于接管HG饱和区域；融合后主图继续进入质量分析、处理和光谱提取。",
        },
    ].filter((item) => Boolean(item.url));
};

const canExtractSpectrum = (frame: ImageFrameRecord | null, useProcessedSource: boolean): boolean =>
    useProcessedSource ? frame?.processedQualityStatus === "PASS" : frame?.qualityStatus === "PASS";

const getSpectrumExtractionDisabledReason = (
    frame: ImageFrameRecord | null,
    useProcessedSource: boolean,
): string | null => {
    if (!frame) {
        return "请选择一张图像。";
    }
    if (useProcessedSource && !frame.processedQualityStatus) {
        return "当前查看的是处理后结果，但这张图像还没有处理后复检结果。";
    }
    if (!canExtractSpectrum(frame, useProcessedSource)) {
        const beforeSourceLabel = frame.calibratedImageDataUrl ? "校准后图像" : originalImageSourceLabel(frame);
        return useProcessedSource
            ? "当前查看的是处理后结果，只有处理后复检质量为PASS时才能提取一维光谱。"
            : `当前查看的是${beforeSourceLabel}，只有质量为PASS时才能提取一维光谱。`;
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

type DisplayStatus = {
    status: string | null;
    label: string;
    prefix: string;
    color: string;
    originalStatus: string | null;
};

const isProcessedFrame = (frame: ImageFrameRecord | null): boolean =>
    Boolean(frame?.processedImageDataUrl) || frame?.processingStatus === "PROCESSED";

const hasProcessedQualityResult = (frame: ImageFrameRecord | null): boolean =>
    Boolean(frame?.processedQualityStatus);

const getDisplayQualityStatus = (frame: ImageFrameRecord | null): DisplayStatus => {
    const useProcessed = hasProcessedQualityResult(frame);
    const status = useProcessed ? frame?.processedQualityStatus ?? null : frame?.qualityStatus ?? null;
    return {
        status,
        label: status || "NOT_EVALUATED",
        prefix: useProcessed ? "复检质量" : "质量",
        color: qualityColor(status),
        originalStatus: frame?.qualityStatus ?? null,
    };
};

const getDisplayDispositionStatus = (frame: ImageFrameRecord | null): DisplayStatus => {
    const useProcessed = hasProcessedQualityResult(frame);
    const status = useProcessed ? frame?.processedDispositionStatus ?? null : frame?.dispositionStatus ?? null;
    return {
        status,
        label: dispositionStatusLabel(status),
        prefix: useProcessed ? "复检处置" : "处置",
        color: dispositionColor(status),
        originalStatus: frame?.dispositionStatus ?? null,
    };
};

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
        return "暂无图像可处理。";
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

const SpectralDataManagementPage: React.FC<SpectralDataManagementPageProps> = ({ scene }) => {
    const { imageHistory } = useJNIStore();
    const [historyScene, setHistoryScene] = useState<HistoryScene>(scene ?? "NORMAL");
    const [hdrHistory, setHdrHistory] = useState<ImageFrameRecord[]>([]);
    const [selectedFrame, setSelectedFrame] = useState<ImageFrameRecord | null>(null);
    const [previewVisible, setPreviewVisible] = useState(false);
    const [hdrPlaneZoomItem, setHdrPlaneZoomItem] = useState<HdrPlanePreviewItem | null>(null);
    const [loading, setLoading] = useState(false);
    const [processingImageId, setProcessingImageId] = useState<number | null>(null);
    const [qualityViewMode, setQualityViewMode] = useState<QualityViewMode>("before");
    const [extractingSpectrumImageId, setExtractingSpectrumImageId] = useState<number | null>(null);
    const [spectrumAxis, setSpectrumAxis] = useState<"AUTO" | "X" | "Y">("AUTO");
    const [spectrumIntegrationMethod, setSpectrumIntegrationMethod] = useState<"MEAN" | "SUM">("MEAN");
    const [spectrumRectifyTilt, setSpectrumRectifyTilt] = useState(true);
    const [spectrumMaxShiftPixels, setSpectrumMaxShiftPixels] = useState<number | null>(null);
    const [spectrumRoi, setSpectrumRoi] = useState<Partial<SpectrumRoi>>({});
    const [spectrumResult, setSpectrumResult] = useState<SpectrumExtractionRecord | null>(null);
    const [globalCalibrationSettings, setGlobalCalibrationSettings] =
        useState<CalibrationGlobalSettingsRecord | null>(null);
    const displayedHistory = historyScene === "HDR" ? hdrHistory : imageHistory;
    const lockedScene = Boolean(scene);
    const pageTitle = historyScene === "HDR" ? "HDR图像管理" : "普通图像管理";
    const pageDescription =
        historyScene === "HDR"
            ? "这里只管理HDR融合主图；HG/LG输入平面可在预览详情中追溯查看"
            : "这里只管理普通单帧图像；HDR融合记录请切换到HDR工作模式查看";
    const calibrationEnabled = historyScene === "HDR"
        ? globalCalibrationSettings?.hdrEnabled
        : globalCalibrationSettings?.enabled;
    const defectMapEnabled = historyScene === "HDR"
        ? globalCalibrationSettings?.hdrDefectMapEnabled
        : globalCalibrationSettings?.defectMapEnabled;

    useEffect(() => {
        if (scene && scene !== historyScene) {
            setHistoryScene(scene);
            setSelectedFrame(null);
            setPreviewVisible(false);
        }
    }, [historyScene, scene]);

    /**
     * 历史图片的唯一数据源是后端数据库和服务器文件系统。
     * 页面刷新后重新请求数据库，不再读取浏览器localStorage中的PNG data URL。
     */
    const loadHistory = async () => {
        setLoading(true);
        try {
            if (historyScene === "HDR") {
                const records = await jniBridgeService.loadHdrImageHistory();
                setHdrHistory(records);
            } else {
                await jniBridgeService.loadImageHistory();
            }
        } catch (error: any) {
            toast.error(error?.message || "加载数据库图片失败");
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        loadHistory();
        jniBridgeService
            .getCalibrationGlobalSettings()
            .then((settings) => {
                setGlobalCalibrationSettings(settings);
            })
            .catch(() => undefined);
    }, [historyScene]);

    useEffect(() => {
        setQualityViewMode(buildProcessedQualitySource(selectedFrame) ? "after" : "before");
        setSpectrumResult(null);
        setSpectrumRoi({});
        setSpectrumAxis("AUTO");
        setSpectrumIntegrationMethod("MEAN");
        setSpectrumRectifyTilt(true);
        setSpectrumMaxShiftPixels(null);
        let cancelled = false;
        if (selectedFrame) {
            jniBridgeService
                .getLatestSpectrum(selectedFrame.id)
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
    }, [selectedFrame]);

    const handleDelete = (record: ImageFrameRecord) => {
        Modal.confirm({
            title: "确认删除",
            content: "将同时删除数据库记录、原始12-bit文件和预览图，确定继续吗？",
            okText: "确认删除",
            cancelText: "取消",
            okButtonProps: { danger: true },
            onOk: async () => {
                await jniBridgeService.deleteImage(record.id);
                if (historyScene === "HDR" || record.captureScene === "HDR") {
                    setHdrHistory((frames) => frames.filter((frame) => frame.id !== record.id));
                }
                if (selectedFrame?.id === record.id) {
                    setSelectedFrame(null);
                    setPreviewVisible(false);
                }
                await loadHistory();
                toast.success("数据库图片已删除");
            },
        });
    };

    const handlePreview = (record: ImageFrameRecord) => {
        setSelectedFrame(record);
        setHdrPlaneZoomItem(null);
        setPreviewVisible(true);
    };

    const handleProcess = async (record: ImageFrameRecord) => {
        const disabledReason = getProcessingDisabledReason(record);
        if (disabledReason) {
            toast.warning(disabledReason);
            return;
        }
        setProcessingImageId(record.id);
        try {
            const processedFrame = await jniBridgeService.processImage(record.id);
            if (historyScene === "HDR" || processedFrame.captureScene === "HDR") {
                setHdrHistory((frames) =>
                    frames.map((frame) => (frame.id === processedFrame.id ? processedFrame : frame)),
                );
            }
            if (selectedFrame?.id === record.id) {
                setSelectedFrame(processedFrame);
            }
            toast.success(processedFrame.processingMessage || "图像处理完成");
        } catch (error: any) {
            toast.error(error?.message || "图像处理失败");
        } finally {
            setProcessingImageId(null);
        }
    };

    const handleExtractSpectrum = async (record: ImageFrameRecord, useProcessedSource: boolean) => {
        const disabledReason = getSpectrumExtractionDisabledReason(record, useProcessedSource);
        if (disabledReason) {
            toast.warning(disabledReason);
            return;
        }

        setExtractingSpectrumImageId(record.id);
        try {
            const sourceMode = useProcessedSource
                ? "PROCESSED"
                : record.calibratedImageDataUrl
                  ? "CALIBRATED"
                  : "ORIGINAL";
            const request: SpectrumExtractionRequest = {
                sourceMode,
                wavelengthAxis: spectrumAxis,
                rectifyTilt: spectrumRectifyTilt,
                integrationMethod: spectrumIntegrationMethod,
                roi: sanitizeRoiDraft(spectrumRoi),
            };
            if (typeof spectrumMaxShiftPixels === "number") {
                request.maxShiftPixels = spectrumMaxShiftPixels;
            }
            const result = await jniBridgeService.extractSpectrum(record.id, request);
            setSpectrumResult(result);
            toast.success(result.summaryMessage || "一维光谱提取完成");
        } catch (error: any) {
            toast.error(error?.message || "一维光谱提取失败");
        } finally {
            setExtractingSpectrumImageId(null);
        }
    };

    const handleClearAll = () => {
        Modal.confirm({
            title: "确认清空",
            content: "确定要清空当前用户的普通单帧和HDR融合图像历史及服务器文件吗？暗场/平场校准包不会在这里删除。此操作不可恢复。",
            okText: "确认清空",
            cancelText: "取消",
            okButtonProps: { danger: true },
            onOk: async () => {
                await jniBridgeService.clearImages();
                setHdrHistory([]);
                setSelectedFrame(null);
                setPreviewVisible(false);
                await loadHistory();
                toast.success("数据库历史已清空");
            },
        });
    };

    const selectedOriginalQualitySource = buildOriginalQualitySource(selectedFrame);
    const selectedProcessedQualitySource = buildProcessedQualitySource(selectedFrame);
    const selectedActiveQualitySource = qualityViewMode === "after" && selectedProcessedQualitySource
        ? selectedProcessedQualitySource
        : selectedOriginalQualitySource;
    const selectedFrameCalibration = objectFromRecord(selectedFrame?.qualityDetails, "calibration");
    const selectedCaptureCalibrationEnabled =
        booleanFromRecord(selectedFrameCalibration, "calibrationPackageEnabled") ??
        booleanFromRecord(selectedFrameCalibration, "globalCalibrationEnabled");
    const selectedCaptureCalibrationApplied = booleanFromRecord(selectedFrameCalibration, "calibrationApplied");
    const selectedCaptureDefectMapEnabled = booleanFromRecord(selectedFrameCalibration, "defectMapEnabled");
    const selectedCaptureDefectMapApplied = booleanFromRecord(selectedFrameCalibration, "defectMapApplied");
    const selectedDarkCalibrationLabel = calibrationReferenceLabel(
        selectedFrameCalibration,
        "darkCalibrationLabel",
        "darkSessionNumber",
        "darkCalibrationId",
        "D",
    );
    const selectedFlatCalibrationLabel = calibrationReferenceLabel(
        selectedFrameCalibration,
        "flatCalibrationLabel",
        "flatSessionNumber",
        "flatCalibrationId",
        "F",
    );
    const selectedDarkCalibrationId = numberFromRecord(selectedFrameCalibration, "darkCalibrationId");
    const selectedFlatCalibrationId = numberFromRecord(selectedFrameCalibration, "flatCalibrationId");
    const selectedDarkCalibrationDeleted = booleanFromRecord(selectedFrameCalibration, "darkCalibrationDeleted");
    const selectedFlatCalibrationDeleted = booleanFromRecord(selectedFrameCalibration, "flatCalibrationDeleted");
    const selectedDeletedCalibrationWarning = stringFromRecord(selectedFrameCalibration, "deletedCalibrationWarning");
    const selectedSummaryUsesProcessed = qualityViewMode === "after" && Boolean(selectedProcessedQualitySource);
    const selectedSummaryQualityStatus = selectedActiveQualitySource?.qualityStatus ?? null;
    const selectedSummaryDispositionStatus = selectedSummaryUsesProcessed
        ? selectedFrame?.processedDispositionStatus ?? null
        : selectedFrame?.dispositionStatus ?? null;
    const selectedBeforeScopeLabel = selectedFrame?.calibratedImageDataUrl ? "校准后" : "原图";
    const selectedSummaryScopeLabel = selectedSummaryUsesProcessed ? "复检" : selectedBeforeScopeLabel;
    const selectedSpectrumSourceMode = selectedSummaryUsesProcessed
        ? "PROCESSED"
        : selectedFrame?.calibratedImageDataUrl
          ? "CALIBRATED"
          : "ORIGINAL";
    const selectedSpectrumSourceLabel = spectrumSourceLabel(selectedSpectrumSourceMode, selectedFrame);
    const selectedSpectrumDisabledReason = getSpectrumExtractionDisabledReason(
        selectedFrame,
        selectedSummaryUsesProcessed,
    );
    const selectedSpectrumResult =
        !selectedSpectrumDisabledReason && spectrumResult?.sourceMode === selectedSpectrumSourceMode
            ? spectrumResult
            : null;
    const selectedSpectrumMismatchMessage =
        !selectedSpectrumDisabledReason && spectrumResult && spectrumResult.sourceMode !== selectedSpectrumSourceMode
            ? `当前已有光谱来自${spectrumSourceLabel(spectrumResult.sourceMode, selectedFrame)}；当前查看的是${selectedSpectrumSourceLabel}，请切换质量视图或重新提取。`
            : null;
    const selectedCounterpartScopeLabel = selectedSummaryUsesProcessed ? selectedBeforeScopeLabel : "复检";
    const selectedCounterpartQualityStatus = selectedSummaryUsesProcessed
        ? selectedFrame?.qualityStatus ?? null
        : selectedFrame?.processedQualityStatus ?? null;
    const selectedCounterpartDispositionStatus = selectedSummaryUsesProcessed
        ? selectedFrame?.dispositionStatus ?? null
        : selectedFrame?.processedDispositionStatus ?? null;
    const selectedQualityMetricExplanations = buildQualityMetricExplanations(selectedActiveQualitySource);
    const selectedProblemQualityMetrics = selectedQualityMetricExplanations.filter(
        (metric) => metric.status === "WARNING" || metric.status === "FAIL",
    );
    const selectedQualityDecisionReasons = getQualityDecisionReasons(selectedActiveQualitySource);
    const selectedHdrPlanePreviewItems = buildHdrPlanePreviewItems(selectedFrame);

    const columns: ColumnsType<ImageFrameRecord> = [
        {
            title: "预览",
            key: "imageDataUrl",
            width: 120,
            render: (_, record) => {
                const previewUrl = record.processedImageDataUrl || record.calibratedImageDataUrl || record.imageDataUrl;
                const previewLabel = record.processedImageDataUrl
                    ? "处理后预览"
                    : record.calibratedImageDataUrl
                      ? "校准后预览"
                      : "原图预览";
                const previewColor = record.processedImageDataUrl
                    ? "green"
                    : record.calibratedImageDataUrl
                      ? "cyan"
                      : "default";
                return (
                    <div className="space-y-1">
                        <div className="flex h-16 w-24 items-center justify-center overflow-hidden rounded bg-slate-950">
                            {previewUrl ? (
                                <img src={previewUrl} alt="图像帧缩略图" className="h-full w-full object-contain" />
                            ) : (
                                <ImageIcon size={22} className="text-slate-400" />
                            )}
                        </div>
                        <Tag color={previewColor} className="m-0 text-[11px]">
                            {previewLabel}
                        </Tag>
                        {record.captureScene === "HDR" && buildHdrPlanePreviewItems(record).length > 0 && (
                            <div className="grid w-24 grid-cols-2 gap-1">
                                {buildHdrPlanePreviewItems(record).map((item) => (
                                    <div
                                        key={item.key}
                                        className="flex h-8 items-center justify-center overflow-hidden rounded bg-slate-950"
                                        title={item.label}
                                    >
                                        <img src={item.url} alt={item.label} className="h-full w-full object-contain" />
                                    </div>
                                ))}
                            </div>
                        )}
                    </div>
                );
            },
        },
        {
            title: "图像信息",
            key: "frame",
            render: (_, record) => {
                const displayQuality = getDisplayQualityStatus(record);
                const displayDisposition = getDisplayDispositionStatus(record);
                const frameCalibration = objectFromRecord(record.qualityDetails, "calibration");
                const hdrDiagnosticStatus = stringFromRecord(record.hdrFusionDetails, "hdrDiagnosticStatus");
                const calibrationEnabled = booleanFromRecord(frameCalibration, "calibrationPackageEnabled");
                const defectMapEnabled = booleanFromRecord(frameCalibration, "defectMapEnabled");
                const calibrationTagColor =
                    calibrationEnabled === true ? (defectMapEnabled ? "cyan" : "blue") : "default";
                const calibrationTagLabel =
                    calibrationEnabled === true
                        ? defectMapEnabled
                            ? "校准+稳定缺陷"
                            : "校准已启用"
                        : calibrationEnabled === false
                          ? "校准未启用"
                          : "校准未记录";
                return (
                    <div>
                        <div className="font-medium text-slate-800">
                            {record.width} x {record.height}
                        </div>
                        <div className="mt-1 text-xs text-slate-500">
                            {record.pixelFormat} · Payload {(record.payloadLength / 1024).toFixed(2)} KB
                        </div>
                        <div className="mt-1 break-all text-xs text-slate-400">
                            读出顺序 {record.readoutOrder || "未记录"}
                        </div>
                        <Tag color={record.captureScene === "HDR" ? "purple" : "blue"} className="mt-2">
                            {record.captureScene === "HDR" ? "HDR融合图像" : "普通单帧"}
                        </Tag>
                        {record.captureScene === "HDR" && (
                            <>
                                <Tag color={qualityColor(hdrDiagnosticStatus)} className="mt-2">
                                    HDR可靠性 {hdrDiagnosticStatus || "未分析"}
                                </Tag>
                                <Tag color="purple" className="mt-2">
                                    增益比 {record.hdrGainRatio?.toFixed(3) ?? "-"}
                                </Tag>
                            </>
                        )}
                        <Tag color={record.integrityPassed ? "green" : "red"} className="mt-2">
                            {record.integrityResultCode || "UNKNOWN"}
                        </Tag>
                        <Tag color={calibrationTagColor} className="mt-2">
                            {calibrationTagLabel}
                        </Tag>
                        <Tag color={displayQuality.color} className="mt-2">
                            {displayQuality.prefix} {displayQuality.label}
                        </Tag>
                        <Tag color={displayDisposition.color} className="mt-2">
                            {displayDisposition.prefix} {displayDisposition.label}
                        </Tag>
                        <Tag color={getProcessingDisplay(record).color} className="mt-2">
                            处理 {getProcessingDisplay(record).label}
                        </Tag>
                        {hasProcessedQualityResult(record) && (
                            <div className="mt-1 text-xs text-slate-400">
                                原图质量 {record.qualityStatus || "NOT_EVALUATED"} · 原图处置{" "}
                                {dispositionStatusLabel(record.dispositionStatus)}
                            </div>
                        )}
                    </div>
                );
            },
        },
        {
            title: "接收时间",
            dataIndex: "timestamp",
            key: "timestamp",
            width: 200,
            render: (timestamp: string) => (
                <div className="flex items-center gap-2 text-slate-600">
                    <Clock size={14} />
                    <span className="text-sm">{new Date(timestamp).toLocaleString("zh-CN")}</span>
                </div>
            ),
        },
        {
            title: "操作",
            key: "action",
            width: 190,
            render: (_, record) => (
                <Space size="small">
                    <Button type="link" size="small" icon={<Eye size={14} />} onClick={() => handlePreview(record)}>
                        预览
                    </Button>
                    <Button
                        size="small"
                        loading={processingImageId === record.id}
                        disabled={
                            Boolean(getProcessingDisabledReason(record)) ||
                            (processingImageId !== null && processingImageId !== record.id)
                        }
                        title={getProcessingDisabledReason(record) || "执行坏点插值或少量异常行/列校正"}
                        onClick={() => handleProcess(record)}
                    >
                        {getProcessingButtonLabel(record, "处理")}
                    </Button>
                    <Button danger size="small" icon={<Trash2 size={14} />} onClick={() => handleDelete(record)}>
                        删除
                    </Button>
                </Space>
            ),
        },
    ];

    return (
        <div className="space-y-6 p-6">
            <Card className="bg-white border border-gray-200 shadow-sm">
                <div className="mb-6 flex flex-wrap items-center justify-between gap-4">
                    <div className="flex items-center gap-3">
                        <div className="p-2.5 bg-green-500 rounded-lg">
                            <Database size={22} className="text-white" />
                        </div>
                        <div>
                            <Title level={4} className="!mb-0 !text-slate-800">
                                {pageTitle}
                            </Title>
                            <Text className="text-sm text-slate-500">{pageDescription}</Text>
                        </div>
                    </div>

                    <div className="flex flex-wrap items-center gap-3">
                        {!lockedScene && (
                            <Segmented
                                value={historyScene}
                                options={[
                                    { label: "普通单帧", value: "NORMAL" },
                                    { label: "HDR融合", value: "HDR" },
                                ]}
                                onChange={(value) => {
                                    setHistoryScene(value as HistoryScene);
                                    setSelectedFrame(null);
                                    setPreviewVisible(false);
                                }}
                            />
                        )}
                        <Tag color={calibrationEnabled ? "cyan" : "default"}>
                            {calibrationEnabled
                                ? defectMapEnabled
                                    ? "校准包 + 稳定缺陷修复已启用"
                                    : "校准包已启用"
                                : "校准包未启用"}
                        </Tag>
                        <Button icon={<RefreshCw size={16} />} onClick={loadHistory} loading={loading}>
                            刷新
                        </Button>
                        <Tag color="blue" className="px-3 py-1 text-sm">
                            共 {displayedHistory.length} 帧
                        </Tag>
                        {displayedHistory.length > 0 && (
                            <Button danger icon={<Trash2 size={16} />} onClick={handleClearAll}>
                                清空全部
                            </Button>
                        )}
                    </div>
                </div>

                {loading && displayedHistory.length === 0 ? (
                    <div className="flex justify-center py-16">
                        <Spin tip="正在加载数据库图片" />
                    </div>
                ) : displayedHistory.length === 0 ? (
                    <Empty
                        image={Empty.PRESENTED_IMAGE_SIMPLE}
                        description={
                            <div className="text-slate-500">
                                <div className="mb-2">
                                    数据库中暂无{historyScene === "HDR" ? "HDR融合" : "普通单帧"}历史图像帧
                                </div>
                                <div className="text-sm">
                                    请先在“{historyScene === "HDR" ? "HDR图像采集" : "普通图像采集"}”页面获取一帧图片
                                </div>
                            </div>
                        }
                        className="py-12"
                    />
                ) : (
                    <Table
                        columns={columns}
                        dataSource={displayedHistory}
                        rowKey="id"
                        loading={loading}
                        pagination={{
                            pageSize: 10,
                            showSizeChanger: true,
                            showTotal: (total) => `共 ${total} 帧`,
                        }}
                    />
                )}
            </Card>

            <Modal
                title={
                    <div className="flex items-center gap-2">
                        <ImageIcon size={18} />
                        <span>数据库图像预览</span>
                        {selectedFrame?.captureScene === "HDR" && <Tag color="purple">HDR融合</Tag>}
                    </div>
                }
                open={previewVisible}
                onCancel={() => {
                    setPreviewVisible(false);
                    setHdrPlaneZoomItem(null);
                }}
                footer={[
                    selectedFrame && (
                        <Button
                            key="process"
                            loading={processingImageId === selectedFrame.id}
                            disabled={
                                Boolean(getProcessingDisabledReason(selectedFrame)) ||
                                (processingImageId !== null && processingImageId !== selectedFrame.id)
                            }
                            title={getProcessingDisabledReason(selectedFrame) || "执行坏点插值或少量异常行/列校正"}
                            onClick={() => handleProcess(selectedFrame)}
                        >
                            {getProcessingButtonLabel(selectedFrame, "处理当前图像")}
                        </Button>
                    ),
                    <Button
                        key="close"
                        onClick={() => {
                            setPreviewVisible(false);
                            setHdrPlaneZoomItem(null);
                        }}
                    >
                        关闭
                    </Button>,
                ]}
                width="96vw"
                style={{ maxWidth: 1480, top: 24 }}
            >
                {selectedFrame && (
                    <div className="grid gap-4 xl:grid-cols-[minmax(0,1.05fr)_minmax(520px,0.95fr)]">
                        <div className="space-y-3">
                            <ImageVersionPreview
                                frame={selectedFrame}
                                defaultVersion={
                                    selectedSummaryUsesProcessed
                                        ? "processed"
                                        : selectedFrame.calibratedImageDataUrl
                                          ? "calibrated"
                                          : "raw"
                                }
                                emptyText="暂无图像帧"
                                imageAreaClassName="h-[52vh] min-h-[420px] max-h-[640px]"
                                rawLabel={selectedFrame.captureScene === "HDR" ? "融合主图" : "原图"}
                                rawDescription={
                                    selectedFrame.captureScene === "HDR"
                                        ? "HG/LG融合后保存的主图，后续质量分析、图像处理和光谱提取均基于它继续执行。"
                                        : "接收后保存的原始预览图，对应 raw16le.bin。"
                                }
                            />

                            {selectedFrame.captureScene === "HDR" && (
                                <Card
                                    size="small"
                                    title="HDR输入平面"
                                    className="border border-slate-200 bg-white"
                                >
                                    {selectedHdrPlanePreviewItems.length > 0 ? (
                                        <>
                                            <div className="grid gap-3 md:grid-cols-2">
                                                {selectedHdrPlanePreviewItems.map((item) => (
                                                    <button
                                                        key={item.key}
                                                        type="button"
                                                        className="overflow-hidden rounded-lg border border-slate-200 bg-slate-950 text-left shadow-sm transition hover:border-cyan-300 hover:shadow-md"
                                                        onClick={() => setHdrPlaneZoomItem(item)}
                                                    >
                                                        <div className="flex aspect-[4/3] items-center justify-center p-2">
                                                            <img
                                                                src={item.url}
                                                                alt={item.label}
                                                                className="h-full w-full object-contain"
                                                            />
                                                        </div>
                                                        <div className="border-t border-slate-800 bg-white px-2.5 py-2">
                                                            <div className="mb-1 flex items-center justify-between gap-2">
                                                                <Text className="font-medium text-slate-800">{item.label}</Text>
                                                                <Tag color={item.tagColor} className="m-0 text-[11px]">
                                                                    点击放大
                                                                </Tag>
                                                            </div>
                                                            <Text className="text-xs leading-5 text-slate-500">
                                                                {item.description}
                                                            </Text>
                                                        </div>
                                                    </button>
                                                ))}
                                            </div>
                                            <Text className="mt-2 block text-xs leading-5 text-slate-500">
                                                HG/LG 是该 HDR 记录的输入追溯数据；融合主图才继续复用普通单帧的质量、处理和光谱提取流程。
                                            </Text>
                                        </>
                                    ) : (
                                        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="该HDR记录没有返回HG/LG预览图" />
                                    )}
                                </Card>
                            )}

                            <div className="grid gap-2 rounded-md bg-gray-50 p-3 sm:grid-cols-2 lg:grid-cols-5 xl:grid-cols-2 2xl:grid-cols-5">
                                <div className="min-w-0 rounded bg-white px-2 py-2">
                                    <Text className="text-xs text-slate-500">采集类型</Text>
                                    <div className="mt-1">
                                        <Tag color={selectedFrame.captureScene === "HDR" ? "purple" : "blue"} className="m-0">
                                            {selectedFrame.captureScene === "HDR" ? "HDR融合图像" : "普通单帧"}
                                        </Tag>
                                    </div>
                                </div>
                                {selectedFrame.captureScene === "HDR" && (
                                    <div className="min-w-0 rounded bg-white px-2 py-2">
                                        <Text className="text-xs text-slate-500">HDR融合</Text>
                                        <div className="mt-1 flex flex-wrap gap-1">
                                            <Tag
                                                color={qualityColor(stringFromRecord(selectedFrame.hdrFusionDetails, "hdrDiagnosticStatus"))}
                                                className="m-0"
                                            >
                                                可靠性 {stringFromRecord(selectedFrame.hdrFusionDetails, "hdrDiagnosticStatus") || "未分析"}
                                            </Tag>
                                            <Tag color="purple" className="m-0">
                                                增益比 {selectedFrame.hdrGainRatio?.toFixed(3) ?? "-"}
                                            </Tag>
                                        </div>
                                    </div>
                                )}
                                <div className="min-w-0 rounded bg-white px-2 py-2">
                                    <Text className="text-xs text-slate-500">尺寸</Text>
                                    <div className="font-semibold text-slate-800">
                                        {selectedFrame.width} x {selectedFrame.height}
                                    </div>
                                </div>
                                <div className="min-w-0 rounded bg-white px-2 py-2">
                                    <Text className="text-xs text-slate-500">像素格式</Text>
                                    <div className="font-semibold text-slate-800">{selectedFrame.pixelFormat}</div>
                                </div>
                                <div className="min-w-0 rounded bg-white px-2 py-2">
                                    <Text className="text-xs text-slate-500">Payload</Text>
                                    <div className="font-semibold text-slate-800">
                                        {(selectedFrame.payloadLength / 1024).toFixed(2)} KB
                                    </div>
                                </div>
                                <div className="min-w-0 rounded bg-white px-2 py-2">
                                    <Text className="text-xs text-slate-500">读出顺序</Text>
                                    <div className="break-all font-semibold text-slate-800">
                                        {selectedFrame.readoutOrder || "未记录"}
                                    </div>
                                </div>
                                <div className="min-w-0 rounded bg-white px-2 py-2">
                                    <Text className="text-xs text-slate-500">完整性</Text>
                                    <div className="break-all font-semibold text-slate-800">
                                        {selectedFrame.integrityResultCode || "UNKNOWN"}
                                    </div>
                                </div>
                                <div className="min-w-0 rounded bg-white px-2 py-2">
                                    <Text className="text-xs text-slate-500">采集校准包</Text>
                                    {selectedCaptureCalibrationEnabled ? (
                                        <div className="mt-1 flex flex-wrap gap-1">
                                            <Tag color="blue" className="m-0 max-w-full whitespace-normal break-all">
                                                暗场 {selectedDarkCalibrationLabel}
                                            </Tag>
                                            <Tag color="purple" className="m-0 max-w-full whitespace-normal break-all">
                                                平场 {selectedFlatCalibrationLabel}
                                            </Tag>
                                        </div>
                                    ) : (
                                        <div className="font-semibold text-slate-800">未启用</div>
                                    )}
                                </div>
                                <div className="min-w-0 rounded bg-white px-2 py-2">
                                    <Text className="text-xs text-slate-500">{selectedSummaryScopeLabel}质量</Text>
                                    <div>
                                        <Tag
                                            color={qualityColor(selectedSummaryQualityStatus)}
                                            className="mt-1 max-w-full whitespace-normal break-all leading-5"
                                            title={selectedSummaryQualityStatus || "NOT_EVALUATED"}
                                        >
                                            {selectedSummaryQualityStatus || "NOT_EVALUATED"}
                                        </Tag>
                                    </div>
                                    {hasProcessedQualityResult(selectedFrame) && (
                                        <div className="mt-1 break-all text-[11px] text-slate-400">
                                            {selectedCounterpartScopeLabel}{" "}
                                            {selectedCounterpartQualityStatus || "NOT_EVALUATED"}
                                        </div>
                                    )}
                                </div>
                                <div className="min-w-0 rounded bg-white px-2 py-2">
                                    <Text className="text-xs text-slate-500">{selectedSummaryScopeLabel}处置</Text>
                                    <div>
                                        <Tag
                                            color={dispositionColor(selectedSummaryDispositionStatus)}
                                            className="mt-1 max-w-full whitespace-normal break-all leading-5"
                                            title={selectedSummaryDispositionStatus || "MANUAL_REVIEW"}
                                        >
                                            {dispositionStatusLabel(selectedSummaryDispositionStatus)}
                                        </Tag>
                                    </div>
                                    {hasProcessedQualityResult(selectedFrame) && (
                                        <div className="mt-1 break-all text-[11px] text-slate-400">
                                            {selectedCounterpartScopeLabel}{" "}
                                            {dispositionStatusLabel(selectedCounterpartDispositionStatus)}
                                            {selectedCounterpartDispositionStatus && (
                                                <span>（{selectedCounterpartDispositionStatus}）</span>
                                            )}
                                        </div>
                                    )}
                                </div>
                                <div className="min-w-0 rounded bg-white px-2 py-2">
                                    <Text className="text-xs text-slate-500">图像处理</Text>
                                    <div>
                                        <Tag
                                            color={getProcessingDisplay(selectedFrame).color}
                                            className="mt-1 max-w-full whitespace-normal break-all leading-5"
                                        >
                                            {getProcessingDisplay(selectedFrame).label}
                                        </Tag>
                                    </div>
                                </div>
                                <div className="min-w-0 rounded bg-white px-2 py-2">
                                    <Text className="mb-1 block text-xs text-slate-500">
                                        {selectedFrame.captureScene === "HDR"
                                            ? "融合主图RAW16像素"
                                            : selectedFrame.readoutOrder === "ROW_MAJOR"
                                              ? "正常行列RAW16像素"
                                              : "重排后RAW16像素"}
                                    </Text>
                                    <Space wrap size={6}>
                                        <ImagePixelDataViewer
                                            frame={selectedFrame}
                                            defaultSource={
                                                selectedSummaryUsesProcessed
                                                    ? "PROCESSED"
                                                    : selectedFrame.calibratedImageDataUrl
                                                      ? "CALIBRATED"
                                                      : "ORIGINAL"
                                            }
                                            triggerLabel={
                                                selectedFrame.captureScene === "HDR"
                                                    ? "查看融合主图RAW16像素"
                                                    : selectedFrame.readoutOrder === "ROW_MAJOR"
                                                      ? "查看正常行列RAW16像素"
                                                    : "查看重排后RAW16像素"
                                            }
                                        />
                                        {shouldShowFpgaPayloadMapping(selectedFrame) && (
                                            <FpgaPayloadDataViewer
                                                frame={selectedFrame}
                                                triggerLabel={fpgaPayloadMappingTriggerLabel(selectedFrame)}
                                            />
                                        )}
                                    </Space>
                                </div>
                                <div className="min-w-0 rounded bg-white px-2 py-2">
                                    <Text className="text-xs text-slate-500">
                                        灰度 min/max/mean（{selectedSummaryScopeLabel}）
                                    </Text>
                                    <div className="font-semibold text-slate-800">
                                        {selectedActiveQualitySource?.pixelMin ?? "-"} /{" "}
                                        {selectedActiveQualitySource?.pixelMax ?? "-"} /{" "}
                                        {formatNullableNumber(selectedActiveQualitySource?.pixelMean ?? null)}
                                    </div>
                                </div>
                                <div className="min-w-0 rounded bg-white px-2 py-2">
                                    <Text className="text-xs text-slate-500">
                                        标准差（{selectedSummaryScopeLabel}）
                                    </Text>
                                    <div className="font-semibold text-slate-800">
                                        {formatNullableNumber(selectedActiveQualitySource?.pixelStddev ?? null)}
                                    </div>
                                </div>
                                <div className="min-w-0 rounded bg-white px-2 py-2">
                                    <Text className="text-xs text-slate-500">
                                        黑/饱和像素（{selectedSummaryScopeLabel}）
                                    </Text>
                                    <div className="font-semibold text-slate-800">
                                        {formatNullableRatio(selectedActiveQualitySource?.blackPixelRatio ?? null)} /{" "}
                                        {formatNullableRatio(selectedActiveQualitySource?.saturationPixelRatio ?? null)}
                                    </div>
                                </div>
                                <div className="min-w-0 rounded bg-white px-2 py-2">
                                    <Text className="text-xs text-slate-500">
                                        异常行/列（{selectedSummaryScopeLabel}）
                                    </Text>
                                    <div className="font-semibold text-slate-800">
                                        {selectedActiveQualitySource?.abnormalRowCount ?? "-"} /{" "}
                                        {selectedActiveQualitySource?.abnormalColumnCount ?? "-"}
                                    </div>
                                </div>
                                <div className="min-w-0 rounded bg-white px-2 py-2">
                                    <Text className="text-xs text-slate-500">坏点（{selectedSummaryScopeLabel}）</Text>
                                    <div className="font-semibold text-slate-800">
                                        {selectedActiveQualitySource?.badPixelCount ?? "-"}
                                    </div>
                                </div>
                            </div>

                            {selectedCaptureCalibrationEnabled && (
                                <div className="rounded-lg border border-cyan-200 bg-cyan-50 p-3">
                                    <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
                                        <Text className="text-sm font-medium text-cyan-800">本帧校准包与缺陷地图审计</Text>
                                        <div className="flex flex-wrap gap-2">
                                            <Tag color="cyan" className="m-0">采集时校准包已锁定</Tag>
                                            {(selectedDarkCalibrationDeleted || selectedFlatCalibrationDeleted) && (
                                                <Tag color="red" className="m-0">历史包已删除</Tag>
                                            )}
                                            <Tag color={selectedCaptureCalibrationApplied ? "green" : "orange"} className="m-0">
                                                {selectedCaptureCalibrationApplied ? "已应用匹配参考" : "未找到匹配参考"}
                                            </Tag>
                                            <Tag
                                                color={selectedCaptureDefectMapApplied ? "green" : selectedCaptureDefectMapEnabled ? "orange" : "default"}
                                                className="m-0"
                                            >
                                                {selectedCaptureDefectMapApplied
                                                    ? "已校正稳定缺陷"
                                                    : selectedCaptureDefectMapEnabled
                                                      ? "稳定缺陷地图无可校正项"
                                                      : "未启用稳定缺陷修复"}
                                            </Tag>
                                        </div>
                                    </div>
                                    <div className="text-xs leading-relaxed text-slate-600">
                                        {stringFromRecord(selectedFrameCalibration, "message") ||
                                            "该图像保存了采集当时的校准包快照，后续全局设置变化不会影响此处追溯。"}
                                    </div>
                                    {selectedDeletedCalibrationWarning && (
                                        <div className="mt-2 text-xs leading-relaxed text-red-600">
                                            {selectedDeletedCalibrationWarning}
                                        </div>
                                    )}
                                    <div className="mt-3 grid gap-2 sm:grid-cols-2">
                                        <div className="rounded-md border border-cyan-100 bg-white/80 p-2.5 text-xs">
                                            <div className="mb-1 text-slate-500">采集锁定暗场</div>
                                            <div className="flex flex-wrap items-center gap-2">
                                                <Tag color="blue" className="m-0">{selectedDarkCalibrationLabel}</Tag>
                                                {selectedDarkCalibrationDeleted && (
                                                    <Tag color="red" className="m-0">已删除</Tag>
                                                )}
                                                {selectedDarkCalibrationId !== null && (
                                                    <span className="text-slate-500">数据库ID {selectedDarkCalibrationId}</span>
                                                )}
                                            </div>
                                        </div>
                                        <div className="rounded-md border border-cyan-100 bg-white/80 p-2.5 text-xs">
                                            <div className="mb-1 text-slate-500">采集锁定平场</div>
                                            <div className="flex flex-wrap items-center gap-2">
                                                <Tag color="purple" className="m-0">{selectedFlatCalibrationLabel}</Tag>
                                                {selectedFlatCalibrationDeleted && (
                                                    <Tag color="red" className="m-0">已删除</Tag>
                                                )}
                                                {selectedFlatCalibrationId !== null && (
                                                    <span className="text-slate-500">数据库ID {selectedFlatCalibrationId}</span>
                                                )}
                                            </div>
                                        </div>
                                    </div>
                                </div>
                            )}
                        </div>

                        <div className="rounded-md border border-slate-200 bg-white p-4">
                            <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
                                <div>
                                    <Title level={5} className="!mb-0 flex items-center gap-2 !text-slate-800">
                                        <Activity size={18} />
                                        质量诊断说明
                                    </Title>
                                    <Text className="text-xs text-slate-500">
                                        与图像并排展示，减少弹窗纵向长度
                                    </Text>
                                </div>
                                <Tag color={qualityColor(selectedActiveQualitySource?.qualityStatus)}>
                                    {selectedActiveQualitySource?.qualityStatus || "NOT_EVALUATED"}
                                </Tag>
                            </div>

                            <div className="mb-4 flex flex-wrap gap-2">
                                <Button
                                    type={qualityViewMode === "before" ? "primary" : "default"}
                                    onClick={() => setQualityViewMode("before")}
                                >
                                    {selectedFrame.calibratedImageDataUrl ? "处理前（校准后）" : "处理前"}
                                </Button>
                                <Button
                                    type={qualityViewMode === "after" ? "primary" : "default"}
                                    disabled={!selectedProcessedQualitySource}
                                    onClick={() => setQualityViewMode("after")}
                                >
                                    处理后
                                </Button>
                            </div>

                            {qualityViewMode === "before" && (
                            <div className="mb-4 rounded-lg border border-blue-200 bg-blue-50 p-3">
                                <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
                                    <Text className="text-sm font-medium text-blue-700">处置建议</Text>
                                    <div className="flex flex-wrap gap-2">
                                        <Tag color={dispositionColor(selectedFrame.dispositionStatus)} className="m-0">
                                            {selectedFrame.dispositionStatus || "MANUAL_REVIEW"}
                                        </Tag>
                                        <Tag color={selectedFrame.usableForSpectral ? "green" : "orange"} className="m-0">
                                            {selectedFrame.usableForSpectral ? "可进入光谱提取" : "暂不进入光谱提取"}
                                        </Tag>
                                    </div>
                                </div>
                                <div className="text-xs leading-relaxed text-slate-600">
                                    {selectedFrame.dispositionMessage || "暂无处置建议，请先完成质量分析。"}
                                </div>
                                {getProcessingDisabledReason(selectedFrame) && (
                                    <div className="mt-2 text-xs leading-relaxed text-orange-600">
                                        处理限制：{getProcessingDisabledReason(selectedFrame)}
                                    </div>
                                )}
                                {selectedFrame.recommendedActions.length > 0 && (
                                    <div className="mt-2 grid gap-2 sm:grid-cols-2">
                                        {selectedFrame.recommendedActions.map((action) => (
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

                            {qualityViewMode === "after" && selectedFrame.processingStatus && (
                                <div className="mb-4 rounded-lg border border-emerald-200 bg-emerald-50 p-3">
                                    <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
                                        <Text className="text-sm font-medium text-emerald-700">处理后复检结果</Text>
                                        <div className="flex flex-wrap gap-2">
                                            <Tag color={processingColor(selectedFrame.processingStatus)} className="m-0">
                                                {processingLabel(selectedFrame.processingStatus)}
                                            </Tag>
                                            {selectedFrame.processedQualityStatus && (
                                                <Tag
                                                    color={qualityColor(selectedFrame.processedQualityStatus)}
                                                    className="m-0"
                                                >
                                                    复检 {selectedFrame.processedQualityStatus}
                                                </Tag>
                                            )}
                                            {typeof selectedFrame.processedUsableForSpectral === "boolean" && (
                                                <Tag
                                                    color={selectedFrame.processedUsableForSpectral ? "green" : "orange"}
                                                    className="m-0"
                                                >
                                                    {selectedFrame.processedUsableForSpectral
                                                        ? "处理后可进入光谱提取"
                                                        : "处理后仍需复核"}
                                                    </Tag>
                                                )}
                                            {selectedFrame.processedDispositionStatus && (
                                                <Tag
                                                    color={dispositionColor(selectedFrame.processedDispositionStatus)}
                                                    className="m-0"
                                                >
                                                    处置 {selectedFrame.processedDispositionStatus}
                                                </Tag>
                                            )}
                                        </div>
                                    </div>
                                    <div className="text-xs leading-relaxed text-slate-600">
                                        {selectedFrame.processedDispositionMessage ||
                                            selectedFrame.processingMessage ||
                                            "已完成当前阶段可执行的图像处理。"}
                                    </div>
                                    {selectedFrame.executedProcessingActions.length > 0 && (
                                        <div className="mt-2 grid gap-2 sm:grid-cols-2">
                                            {selectedFrame.executedProcessingActions.map((action) => (
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

                            <div className="mb-4 rounded-lg border border-cyan-200 bg-cyan-50 p-3">
                                <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
                                    <div>
                                        <Text className="block text-sm font-medium text-cyan-800">一维光谱提取</Text>
                                        <Text className="text-xs text-cyan-700">
                                            输出 pixelIndex-intensity；波长 nm 标定留到后续标定模块
                                        </Text>
                                    </div>
                                    <Tag color={selectedSpectrumDisabledReason ? "orange" : "green"} className="m-0">
                                        {selectedSpectrumDisabledReason ? "等待当前视图PASS" : `可提取·${selectedSpectrumSourceLabel}`}
                                    </Tag>
                                </div>

                                <div className="grid gap-2 md:grid-cols-2 xl:grid-cols-3">
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
                                            disabled={Boolean(selectedSpectrumDisabledReason)}
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
                                            disabled={Boolean(selectedSpectrumDisabledReason)}
                                            onChange={(value) =>
                                                setSpectrumIntegrationMethod(value as "MEAN" | "SUM")
                                            }
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
                                            disabled={Boolean(selectedSpectrumDisabledReason)}
                                            onChange={(value) =>
                                                setSpectrumMaxShiftPixels(typeof value === "number" ? value : null)
                                            }
                                        />
                                    </div>
                                </div>

                                <div className="mt-3 grid gap-2 md:grid-cols-4">
                                    {(["xStart", "xEnd", "yStart", "yEnd"] as const).map((key) => (
                                        <div key={key}>
                                            <Text className="mb-1 block text-xs text-slate-500">{key}</Text>
                                            <InputNumber
                                                value={spectrumRoi[key]}
                                                min={0}
                                                max={key.startsWith("x") ? selectedFrame.width : selectedFrame.height}
                                                className="w-full"
                                                disabled={Boolean(selectedSpectrumDisabledReason)}
                                                placeholder={
                                                    key === "xStart"
                                                        ? "0"
                                                        : key === "xEnd"
                                                          ? String(selectedFrame.width)
                                                          : key === "yStart"
                                                            ? "0"
                                                            : String(selectedFrame.height)
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

                                <div className="mt-3 flex flex-wrap items-center justify-between gap-3">
                                    <Checkbox
                                        checked={spectrumRectifyTilt}
                                        disabled={Boolean(selectedSpectrumDisabledReason)}
                                        onChange={(event) => setSpectrumRectifyTilt(event.target.checked)}
                                    >
                                        提取前进行轻微倾斜矫正
                                    </Checkbox>
                                    <Button
                                        type="primary"
                                        loading={extractingSpectrumImageId === selectedFrame.id}
                                        disabled={
                                            Boolean(selectedSpectrumDisabledReason) || extractingSpectrumImageId !== null
                                        }
                                        title={selectedSpectrumDisabledReason || `从${selectedSpectrumSourceLabel}提取一维像素域光谱`}
                                        onClick={() => handleExtractSpectrum(selectedFrame, selectedSummaryUsesProcessed)}
                                    >
                                        提取一维光谱
                                    </Button>
                                </div>

                                {selectedSpectrumDisabledReason && (
                                    <div className="mt-2 text-xs leading-relaxed text-orange-700">
                                        {selectedSpectrumDisabledReason}
                                    </div>
                                )}

                                {selectedSpectrumMismatchMessage && (
                                    <div className="mt-2 text-xs leading-relaxed text-cyan-700">
                                        {selectedSpectrumMismatchMessage}
                                    </div>
                                )}

                                {selectedSpectrumResult && (
                                    <div className="mt-4 space-y-3 rounded-lg border border-cyan-100 bg-white p-3">
                                        <div className="flex flex-wrap items-center gap-2 text-xs text-slate-600">
                                            <Tag color="blue" className="m-0">
                                                来源 {selectedSpectrumResult.sourceMode}
                                            </Tag>
                                            <Tag color="cyan" className="m-0">
                                                方向 {selectedSpectrumResult.wavelengthAxis}
                                            </Tag>
                                            <Tag color="purple" className="m-0">
                                                点数 {selectedSpectrumResult.pointCount}
                                            </Tag>
                                            <Tag color="geekblue" className="m-0">
                                                偏移 {selectedSpectrumResult.shiftMin}~{selectedSpectrumResult.shiftMax}px
                                            </Tag>
                                        </div>
                                        <SpectrumCurve spectrum={selectedSpectrumResult} />
                                        <div className="grid gap-2 text-xs text-slate-600 sm:grid-cols-3">
                                            <div>最小强度：{formatNullableNumber(selectedSpectrumResult.intensityMin)}</div>
                                            <div>最大强度：{formatNullableNumber(selectedSpectrumResult.intensityMax)}</div>
                                            <div>平均强度：{formatNullableNumber(selectedSpectrumResult.intensityMean)}</div>
                                        </div>
                                        <div className="text-xs leading-relaxed text-slate-500">
                                            {selectedSpectrumResult.summaryMessage}
                                        </div>
                                    </div>
                                )}
                            </div>

                            {selectedActiveQualitySource?.qualityStatus !== "PASS" && (
                                <div className="mb-4 rounded-lg border border-orange-200 bg-orange-50 p-3">
                                    <Text className="block text-sm font-medium text-orange-700">
                                        导致当前{qualityViewMode === "after" ? "复检" : ""}状态不是 PASS 的指标
                                    </Text>
                                    {selectedProblemQualityMetrics.length > 0 ? (
                                        <div className="mt-2 grid gap-2 sm:grid-cols-2">
                                            {selectedProblemQualityMetrics.map((metric) => (
                                                <div
                                                    key={metric.key}
                                                    className="rounded-md bg-white/80 p-2.5 text-sm"
                                                >
                                                    <div className="mb-1 flex items-center gap-2">
                                                        <Tag color={metricColor(metric.status)} className="m-0">
                                                            {metric.status}
                                                        </Tag>
                                                        <span className="font-medium text-slate-800">
                                                            {metric.label}
                                                        </span>
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
                                            {selectedActiveQualitySource?.qualitySummaryMessage ||
                                                selectedQualityDecisionReasons.join("；") ||
                                                "后端返回了非PASS状态，但没有提供具体原因。"}
                                        </div>
                                    )}
                                </div>
                            )}

                            <div className="grid gap-2 md:grid-cols-2">
                                {selectedQualityMetricExplanations.map((metric) => (
                                    <div key={metric.key} className="rounded-lg border border-slate-200 bg-slate-50 p-2.5">
                                        <div className="mb-1.5 flex flex-wrap items-center justify-between gap-2">
                                            <div className="flex items-center gap-2">
                                                <Tag color={metricColor(metric.status)} className="m-0">
                                                    {metric.status}
                                                </Tag>
                                                <span className="font-medium text-slate-800">{metric.label}</span>
                                            </div>
                                            <span className="font-mono text-xs text-slate-700">{metric.value}</span>
                                        </div>
                                        <div className="space-y-1 text-[11px] leading-relaxed text-slate-500">
                                            <div>
                                                <span className="font-medium text-slate-600">合格：</span>
                                                {metric.healthyCondition}
                                            </div>
                                            <div>
                                                <span className="font-medium text-slate-600">原因：</span>
                                                {metric.unhealthyReason}
                                            </div>
                                            <div>
                                                <span className="font-medium text-slate-600">参考：</span>
                                                {metric.reference}
                                            </div>
                                        </div>
                                    </div>
                                ))}
                            </div>
                        </div>
                    </div>
                )}
            </Modal>

            <Modal
                title={hdrPlaneZoomItem?.label ?? "HDR输入平面预览"}
                open={Boolean(hdrPlaneZoomItem)}
                onCancel={() => setHdrPlaneZoomItem(null)}
                footer={<Button type="primary" onClick={() => setHdrPlaneZoomItem(null)}>关闭</Button>}
                width="92vw"
                style={{ maxWidth: 1400, top: 24 }}
                destroyOnClose
            >
                <div className="space-y-3">
                    <div className="h-[72vh] overflow-auto rounded-lg bg-slate-950 p-4 text-center">
                        {hdrPlaneZoomItem && (
                            <img
                                src={hdrPlaneZoomItem.url}
                                alt={hdrPlaneZoomItem.label}
                                className="inline-block max-h-full max-w-full object-contain align-middle"
                            />
                        )}
                    </div>
                    {hdrPlaneZoomItem && (
                        <Text className="block text-xs text-slate-500">{hdrPlaneZoomItem.description}</Text>
                    )}
                </div>
            </Modal>
        </div>
    );
};

export default SpectralDataManagementPage;
