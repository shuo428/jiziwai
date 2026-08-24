import React, { useEffect, useMemo, useState } from "react";
import { Alert, Button, Card, Checkbox, Empty, InputNumber, Modal, Select, Space, Spin, Tag, Typography } from "antd";
import { Activity, Camera, Layers, Zap } from "lucide-react";
import { toast } from "sonner";

import { jniBridgeService } from "../service/jniBridgeService";
import { useJNIStore } from "../store/jniStore";
import FpgaPayloadDataViewer from "../components/FpgaPayloadDataViewer";
import ImagePixelDataViewer from "../components/ImagePixelDataViewer";
import ImageVersionPreview from "../components/ImageVersionPreview";
import SpectrumCurve from "../components/SpectrumCurve";
import type { ImageFrameRecord, SpectrumExtractionRecord, SpectrumExtractionRequest, SpectrumRoi } from "../types/jni";

const { Title, Text } = Typography;

type PreviewItem = {
    key: string;
    label: string;
    url: string;
    tagColor: string;
    description: string;
};

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

const numberFromDetails = (details: Record<string, unknown> | null | undefined, key: string): number | null => {
    const value = details?.[key];
    return typeof value === "number" ? value : null;
};

const stringFromDetails = (details: Record<string, unknown> | null | undefined, key: string): string | null => {
    const value = details?.[key];
    return typeof value === "string" ? value : null;
};

const formatCount = (value: number | null): string => (value === null ? "-" : value.toLocaleString("zh-CN"));

const formatNumber = (value: number | null | undefined, digits = 2): string =>
    typeof value === "number" ? value.toFixed(digits) : "-";

const formatRatio = (value: number | null | undefined, digits = 3): string =>
    typeof value === "number" ? `${(value * 100).toFixed(digits)}%` : "-";

const booleanFromDetails = (details: Record<string, unknown> | null | undefined, key: string): boolean | null => {
    const value = details?.[key];
    return typeof value === "boolean" ? value : null;
};

const stringArrayFromDetails = (details: Record<string, unknown> | null | undefined, key: string): string[] => {
    const value = details?.[key];
    return Array.isArray(value) ? value.filter((item): item is string => typeof item === "string") : [];
};

const diagnosticColor = (status?: string | null): string => {
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

const diagnosticAlertType = (status?: string | null): "success" | "warning" | "error" | "info" => {
    if (status === "PASS") {
        return "success";
    }
    if (status === "WARNING") {
        return "warning";
    }
    if (status === "FAIL") {
        return "error";
    }
    return "info";
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

type QualityViewMode = "before" | "after";
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

const formatNullableNumber = (value: number | null | undefined, digits = 2): string =>
    typeof value === "number" ? value.toFixed(digits) : "-";

const formatNullableRatio = (value: number | null | undefined): string =>
    typeof value === "number" ? `${(value * 100).toFixed(4)}%` : "-";

const numberFromRecord = (record: Record<string, unknown> | null | undefined, key: string): number | null => {
    const value = record?.[key];
    return typeof value === "number" ? value : null;
};

const stringFromRecord = (record: Record<string, unknown> | null | undefined, key: string): string | null => {
    const value = record?.[key];
    return typeof value === "string" ? value : null;
};

const objectFromRecord = (
    record: Record<string, unknown> | null | undefined,
    key: string,
): Record<string, unknown> | null => {
    const value = record?.[key];
    return value && typeof value === "object" ? (value as Record<string, unknown>) : null;
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
        return "暂无HDR融合主图可处理，请先获取一帧HDR。";
    }
    if (isProcessedFrame(frame)) {
        return "当前HDR融合主图已完成图像处理，无需重复处理。";
    }
    if (frame.processingStatus === "SKIPPED") {
        return frame.processingMessage || "当前HDR融合主图没有可自动修复动作，或无需修复。";
    }
    if (
        frame.qualityStatus === "FAIL" ||
        frame.dispositionStatus === "RECAPTURE_RECOMMENDED" ||
        frame.dispositionStatus === "REJECTED"
    ) {
        return "当前HDR融合主图质量为FAIL或建议重采，自动处理不会恢复真实光谱信息。";
    }
    if (!hasExecutableProcessingAction(frame)) {
        return "当前HDR融合主图没有可自动处理的坏点或少量异常行/列。";
    }
    return null;
};

const getProcessingButtonLabel = (frame: ImageFrameRecord | null): string => {
    if (!frame) {
        return "处理融合主图";
    }
    if (isProcessedFrame(frame)) {
        return "已处理";
    }
    if (
        frame.qualityStatus === "FAIL" ||
        frame.dispositionStatus === "RECAPTURE_RECOMMENDED" ||
        frame.dispositionStatus === "REJECTED"
    ) {
        return "不可处理";
    }
    if (frame.processingStatus === "SKIPPED" || frame.qualityStatus === "PASS" || frame.dispositionStatus === "USE_AS_IS") {
        return "无需处理";
    }
    if (!hasExecutableProcessingAction(frame)) {
        return "无可处理项";
    }
    return "处理融合主图";
};

const getProcessingStateColor = (frame: ImageFrameRecord | null): string => {
    if (!frame) {
        return "default";
    }
    if (isProcessedFrame(frame)) {
        return "green";
    }
    if (
        frame.qualityStatus === "FAIL" ||
        frame.dispositionStatus === "RECAPTURE_RECOMMENDED" ||
        frame.dispositionStatus === "REJECTED"
    ) {
        return "red";
    }
    if (hasExecutableProcessingAction(frame)) {
        return "orange";
    }
    return "blue";
};

const getProcessingStateText = (frame: ImageFrameRecord | null): string => {
    if (!frame) {
        return "暂无图像";
    }
    if (isProcessedFrame(frame)) {
        return "已处理";
    }
    if (
        frame.qualityStatus === "FAIL" ||
        frame.dispositionStatus === "RECAPTURE_RECOMMENDED" ||
        frame.dispositionStatus === "REJECTED"
    ) {
        return "不可处理";
    }
    if (hasExecutableProcessingAction(frame)) {
        return "可处理";
    }
    return "无需自动处理";
};

const getSpectrumExtractionDisabledReason = (frame: ImageFrameRecord | null): string | null => {
    if (!frame) {
        return "暂无HDR融合主图可提取，请先获取一帧HDR。";
    }
    const hdrStatus = stringFromDetails(frame.hdrFusionDetails, "hdrDiagnosticStatus");
    if (hdrStatus === "FAIL") {
        return "当前HDR融合可靠性为FAIL，说明HG/LG双饱和或融合本身不可靠，不建议提取一维光谱。";
    }
    if (frame.qualityStatus !== "PASS" && frame.processedQualityStatus !== "PASS") {
        return "只有HDR融合主图质量PASS，或处理后复检PASS，才能提取一维光谱。";
    }
    return null;
};

const canExtractSpectrum = (frame: ImageFrameRecord | null): boolean =>
    !getSpectrumExtractionDisabledReason(frame);

const HdrCapturePage: React.FC = () => {
    const { bridgeState, currentHdrImage, actions } = useJNIStore();
    const currentFrame = currentHdrImage;
    const setCurrentFrame = actions.setCurrentHdrImage;
    const [loadingLatestFrame, setLoadingLatestFrame] = useState(false);
    const [capturing, setCapturing] = useState(false);
    const [loadingAction, setLoadingAction] = useState<string | null>(null);
    const [autoProcess, setAutoProcess] = useState(false);
    const [qualityViewMode, setQualityViewMode] = useState<QualityViewMode>("before");
    const [spectrumAxis, setSpectrumAxis] = useState<"AUTO" | "X" | "Y">("AUTO");
    const [spectrumIntegrationMethod, setSpectrumIntegrationMethod] = useState<"MEAN" | "SUM">("MEAN");
    const [spectrumRectifyTilt, setSpectrumRectifyTilt] = useState(true);
    const [spectrumMaxShiftPixels, setSpectrumMaxShiftPixels] = useState<number | null>(null);
    const [spectrumRoi, setSpectrumRoi] = useState<Partial<SpectrumRoi>>({});
    const [spectrumResult, setSpectrumResult] = useState<SpectrumExtractionRecord | null>(null);
    const [zoomItem, setZoomItem] = useState<PreviewItem | null>(null);

    useEffect(() => {
        let cancelled = false;
        setLoadingLatestFrame(true);
        jniBridgeService
            .loadHdrImageHistory()
            .then((records) => {
                if (!cancelled) {
                    setCurrentFrame(records[0] ?? null);
                }
            })
            .catch((error) => {
                if (!cancelled) {
                    toast.error(error instanceof Error ? error.message : "加载最新HDR图像失败");
                }
            })
            .finally(() => {
                if (!cancelled) {
                    setLoadingLatestFrame(false);
                }
            });
        return () => {
            cancelled = true;
        };
    }, []);

    useEffect(() => {
        if (!currentFrame?.id) {
            setSpectrumResult(null);
            return;
        }
        setQualityViewMode(currentFrame.processedQualityStatus ? "after" : "before");
        setSpectrumRoi({});
        setSpectrumAxis("AUTO");
        setSpectrumIntegrationMethod("MEAN");
        setSpectrumRectifyTilt(true);
        setSpectrumMaxShiftPixels(null);
        jniBridgeService.getLatestSpectrum(currentFrame.id)
            .then((result) => setSpectrumResult(result))
            .catch(() => setSpectrumResult(null));
    }, [currentFrame?.id]);

    const previewItems = useMemo<PreviewItem[]>(() => {
        if (!currentFrame) {
            return [];
        }
        return [
            {
                key: "hg",
                label: "HG 高增益平面",
                url: currentFrame.hgImageDataUrl,
                tagColor: "geekblue",
                description: "一次触发 payload 前半段，native 已按当前读出顺序重排为正常行列坐标。",
            },
            {
                key: "lg",
                label: "LG 低增益平面",
                url: currentFrame.lgImageDataUrl,
                tagColor: "purple",
                description: "一次触发 payload 后半段，native 已按当前读出顺序重排为正常行列坐标。",
            },
        ].filter((item) => Boolean(item.url));
    }, [currentFrame]);

    const handleCapture = async () => {
        setCapturing(true);
        try {
            const frame = await jniBridgeService.triggerOnceAndWaitForFrame({
                autoProcess,
                captureScene: "HDR",
            });
            setCurrentFrame(frame);
            toast.success("HDR双增益图像采集完成");
        } catch (error) {
            toast.error(error instanceof Error ? error.message : "HDR采集失败");
        } finally {
            setCapturing(false);
        }
    };

    const updateCurrentFrame = (frame: ImageFrameRecord) => {
        actions.pushHdrImageFrame(frame);
    };

    const handleProcess = async () => {
        if (!currentFrame) {
            toast.warning("暂无HDR融合主图可处理。");
            return;
        }
        const disabledReason = getProcessingDisabledReason(currentFrame);
        if (disabledReason) {
            toast.warning(disabledReason);
            return;
        }
        setLoadingAction("process");
        try {
            const frame = await jniBridgeService.processImage(currentFrame.id);
            updateCurrentFrame(frame);
            setQualityViewMode("after");
            toast.success("HDR融合主图处理完成");
        } catch (error) {
            toast.error(error instanceof Error ? error.message : "HDR融合主图处理失败");
        } finally {
            setLoadingAction(null);
        }
    };

    const handleExtractSpectrum = async () => {
        if (!currentFrame) {
            toast.warning("暂无HDR融合主图可提取。");
            return;
        }
        const disabledReason = getSpectrumExtractionDisabledReason(currentFrame);
        if (disabledReason) {
            toast.warning(disabledReason);
            return;
        }
        setLoadingAction("spectrum");
        try {
            const result = await jniBridgeService.extractSpectrum(currentFrame.id, {
                sourceMode: "AUTO",
                wavelengthAxis: spectrumAxis,
                rectifyTilt: spectrumRectifyTilt,
                integrationMethod: spectrumIntegrationMethod,
                roi: sanitizeRoiDraft(spectrumRoi),
                ...(typeof spectrumMaxShiftPixels === "number" ? { maxShiftPixels: spectrumMaxShiftPixels } : {}),
            });
            setSpectrumResult(result);
            toast.success("HDR融合主图一维光谱提取完成");
        } catch (error) {
            toast.error(error instanceof Error ? error.message : "一维光谱提取失败");
        } finally {
            setLoadingAction(null);
        }
    };

    const details = currentFrame?.hdrFusionDetails;
    const hdrDiagnosticStatus = stringFromDetails(details, "hdrDiagnosticStatus");
    const hdrDiagnosticReasons = stringArrayFromDetails(details, "hdrDiagnosticReasons");
    const hgPixelCount = numberFromDetails(details, "hgPixelCount");
    const lgPixelCount = numberFromDetails(details, "lgPixelCount");
    const blendPixelCount = numberFromDetails(details, "blendPixelCount");
    const bothSaturatedCount = numberFromDetails(details, "bothSaturatedCount");
    const hgUsedRatio = numberFromDetails(details, "hgUsedRatio");
    const lgUsedRatio = numberFromDetails(details, "lgUsedRatio");
    const blendRatio = numberFromDetails(details, "blendRatio");
    const bothSaturatedRatio = numberFromDetails(details, "bothSaturatedRatio");
    const hgSaturatedRatio = numberFromDetails(details, "hgSaturatedRatio");
    const lgSaturatedRatio = numberFromDetails(details, "lgSaturatedRatio");
    const gainSampleCount = numberFromDetails(details, "gainSampleCount");
    const gainSampleRatio = numberFromDetails(details, "gainSampleRatio");
    const gainSampleMinimum = numberFromDetails(details, "gainSampleMinimum");
    const gainRatioFallback = booleanFromDetails(details, "gainRatioFallback");
    const lgCompensationRatio = numberFromDetails(details, "lgCompensationRatio");
    const blendStartHgDn = numberFromDetails(details, "blendStartHgDn");
    const blendEndHgDn = numberFromDetails(details, "blendEndHgDn");
    const warningBothSaturatedRatio = numberFromDetails(details, "warningBothSaturatedRatio");
    const failBothSaturatedRatio = numberFromDetails(details, "failBothSaturatedRatio");
    const warningLgSaturatedRatio = numberFromDetails(details, "warningLgSaturatedRatio");
    const storagePolicy = stringFromDetails(details, "fusedStoragePolicy");
    const processingDisabledReason = getProcessingDisabledReason(currentFrame);
    const spectrumDisabledReason = getSpectrumExtractionDisabledReason(currentFrame);
    const currentOriginalQualitySource = useMemo(
        () => buildOriginalQualitySource(currentFrame),
        [currentFrame],
    );
    const currentProcessedQualitySource = useMemo(
        () => buildProcessedQualitySource(currentFrame),
        [currentFrame],
    );
    const currentActiveQualitySource =
        qualityViewMode === "after" && currentProcessedQualitySource
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
    const hasProcessedQuality = Boolean(currentFrame?.processedQualityStatus);
    const activeQualityStatus =
        qualityViewMode === "after" && hasProcessedQuality
            ? currentFrame?.processedQualityStatus ?? null
            : currentFrame?.qualityStatus ?? null;
    const activeDispositionStatus =
        qualityViewMode === "after" && hasProcessedQuality
            ? currentFrame?.processedDispositionStatus ?? null
            : currentFrame?.dispositionStatus ?? null;
    const activeQualitySummaryMessage =
        qualityViewMode === "after" && hasProcessedQuality
            ? currentFrame?.processedQualitySummaryMessage ?? currentFrame?.processedDispositionMessage ?? null
            : currentFrame?.qualitySummaryMessage ?? currentFrame?.dispositionMessage ?? null;
    const activePixelMin =
        qualityViewMode === "after" && hasProcessedQuality ? currentFrame?.processedPixelMin : currentFrame?.pixelMin;
    const activePixelMax =
        qualityViewMode === "after" && hasProcessedQuality ? currentFrame?.processedPixelMax : currentFrame?.pixelMax;
    const activePixelMean =
        qualityViewMode === "after" && hasProcessedQuality ? currentFrame?.processedPixelMean : currentFrame?.pixelMean;
    const activePixelStddev =
        qualityViewMode === "after" && hasProcessedQuality ? currentFrame?.processedPixelStddev : currentFrame?.pixelStddev;
    const activeBlackPixelRatio =
        qualityViewMode === "after" && hasProcessedQuality
            ? currentFrame?.processedBlackPixelRatio
            : currentFrame?.blackPixelRatio;
    const activeSaturationPixelRatio =
        qualityViewMode === "after" && hasProcessedQuality
            ? currentFrame?.processedSaturationPixelRatio
            : currentFrame?.saturationPixelRatio;
    const activeBadPixelCount =
        qualityViewMode === "after" && hasProcessedQuality
            ? currentFrame?.processedBadPixelCount
            : currentFrame?.badPixelCount;
    const activeAbnormalRowCount =
        qualityViewMode === "after" && hasProcessedQuality
            ? currentFrame?.processedAbnormalRowCount
            : currentFrame?.abnormalRowCount;
    const activeAbnormalColumnCount =
        qualityViewMode === "after" && hasProcessedQuality
            ? currentFrame?.processedAbnormalColumnCount
            : currentFrame?.abnormalColumnCount;

    return (
        <div className="space-y-5 p-6">
            <div className="flex flex-wrap items-start justify-between gap-4">
                <div>
                    <Title level={3} className="!mb-1 !text-slate-800">
                        HDR 高低增益采集与融合
                    </Title>
                    <Text className="text-slate-500">
                        一次触发接收 HG+LG 双平面：先 HG 所有行，再 LG 所有行；native 负责拆分和重排，Java 负责融合和入库。
                    </Text>
                </div>
                <Space wrap>
                    <Tag color={bridgeState.connected ? "green" : "default"}>
                        {bridgeState.connected ? "设备已连接" : "设备未连接"}
                    </Tag>
                    <Tag color="cyan">
                        {bridgeState.expectedWidth}×{bridgeState.expectedHeight}
                    </Tag>
                    <Tag color="blue">{bridgeState.readoutOrder}</Tag>
                </Space>
            </div>

            <Card className="border border-slate-200 bg-white shadow-sm">
                <div className="flex flex-wrap items-center justify-between gap-4">
                    <div>
                        <Title level={5} className="!mb-1 flex items-center gap-2 !text-slate-800">
                            <Zap size={18} />
                            HDR 触发采集
                        </Title>
                        <Text className="text-sm text-slate-500">
                            当前版本要求 FPGA 图像 payload 长度为 width × height × 2 × 2 字节。
                        </Text>
                    </div>
                    <Space wrap>
                        <Checkbox checked={autoProcess} onChange={(event) => setAutoProcess(event.target.checked)}>
                            融合后自动处理
                        </Checkbox>
                        <Button
                            type="primary"
                            icon={<Camera size={16} />}
                            disabled={!bridgeState.connected}
                            loading={capturing}
                            onClick={handleCapture}
                        >
                            获取一帧HDR
                        </Button>
                    </Space>
                </div>
            </Card>

            {!bridgeState.connected && (
                <Alert
                    type="warning"
                    showIcon
                    message="设备未连接"
                    description="请先在“设备总览”中连接 FPGA/CMOS 图像通道，再执行 HDR 采集。"
                />
            )}

            <div className="space-y-5">
                <Card className="border border-slate-200 bg-white shadow-sm">
                    <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
                        <Title level={5} className="!mb-0 flex items-center gap-2 !text-slate-800">
                            <Layers size={18} />
                            当前 HDR 图像
                        </Title>
                        {currentFrame && (
                            <Space wrap size={[6, 6]}>
                                <Tag color="blue">HDR</Tag>
                                <Tag color={qualityColor(currentFrame.qualityStatus)}>
                                    质量 {currentFrame.qualityStatus ?? "未分析"}
                                </Tag>
                                <Tag color={diagnosticColor(hdrDiagnosticStatus)}>
                                    HDR可靠性 {hdrDiagnosticStatus ?? "未分析"}
                                </Tag>
                                <Tag color="purple">
                                    增益比 {currentFrame.hdrGainRatio?.toFixed(3) ?? "-"}
                                </Tag>
                                <Button
                                    loading={loadingAction === "process"}
                                    disabled={Boolean(processingDisabledReason) || loadingAction !== null || capturing}
                                    onClick={handleProcess}
                                >
                                    {getProcessingButtonLabel(currentFrame)}
                                </Button>
                            </Space>
                        )}
                    </div>

                    {currentFrame ? (
                        <div className="space-y-4">
                            <Card
                                size="small"
                                title="HDR输入平面"
                                className="border border-slate-200 bg-white"
                            >
                                <div className="grid gap-4 md:grid-cols-2">
                                    {previewItems.map((item) => (
                                        <button
                                            key={item.key}
                                            type="button"
                                            className="overflow-hidden rounded-xl border border-slate-200 bg-slate-950 text-left shadow-sm transition hover:border-cyan-300 hover:shadow-md"
                                            onClick={() => setZoomItem(item)}
                                        >
                                            <div className="flex aspect-[4/3] items-center justify-center p-3">
                                                <img src={item.url} alt={item.label} className="h-full w-full object-contain" />
                                            </div>
                                            <div className="border-t border-slate-800 bg-white px-3 py-2">
                                                <div className="mb-1 flex items-center justify-between gap-2">
                                                    <Text className="font-medium text-slate-800">{item.label}</Text>
                                                    <Tag color={item.tagColor} className="m-0 text-[11px]">
                                                        点击放大
                                                    </Tag>
                                                </div>
                                                <Text className="text-xs leading-5 text-slate-500">{item.description}</Text>
                                            </div>
                                        </button>
                                    ))}
                                </div>
                                <Text className="mt-2 block text-xs leading-5 text-slate-500">
                                    HG/LG 是 HDR 采集特有的追溯输入平面；系统后续质量分析、图像处理和光谱提取使用的是融合主图。
                                </Text>
                            </Card>

                            <Card
                                size="small"
                                title={
                                    <div className="flex flex-wrap items-center justify-between gap-2">
                                        <span>HG/LG输入平面与融合可靠性诊断</span>
                                        <Tag color={diagnosticColor(hdrDiagnosticStatus)} className="m-0">
                                            {hdrDiagnosticStatus ?? "未分析"}
                                        </Tag>
                                    </div>
                                }
                                className="border border-slate-200"
                            >
                                <div className="space-y-3">
                                    <Alert
                                        type={diagnosticAlertType(hdrDiagnosticStatus)}
                                        showIcon
                                        message={
                                            hdrDiagnosticStatus
                                                ? `HDR融合可靠性${hdrDiagnosticStatus}`
                                                : "HDR融合可靠性暂未返回"
                                        }
                                        description={
                                            hdrDiagnosticReasons.length > 0
                                                ? hdrDiagnosticReasons.join("；")
                                                : "等待后端返回HDR融合诊断原因。"
                                        }
                                    />

                                    <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
                                        <Card size="small" className="bg-slate-50">
                                            <Text className="block text-xs text-slate-500">HG使用像素</Text>
                                            <Text className="font-semibold text-slate-800">{formatCount(hgPixelCount)}</Text>
                                            <Text className="mt-1 block text-[11px] text-slate-400">{formatRatio(hgUsedRatio)}</Text>
                                        </Card>
                                        <Card size="small" className="bg-slate-50">
                                            <Text className="block text-xs text-slate-500">LG使用像素</Text>
                                            <Text className="font-semibold text-slate-800">{formatCount(lgPixelCount)}</Text>
                                            <Text className="mt-1 block text-[11px] text-slate-400">{formatRatio(lgUsedRatio)}</Text>
                                        </Card>
                                        <Card size="small" className="bg-slate-50">
                                            <Text className="block text-xs text-slate-500">混合过渡像素</Text>
                                            <Text className="font-semibold text-slate-800">{formatCount(blendPixelCount)}</Text>
                                            <Text className="mt-1 block text-[11px] text-slate-400">{formatRatio(blendRatio)}</Text>
                                        </Card>
                                        <Card size="small" className="bg-slate-50">
                                            <Text className="block text-xs text-slate-500">双饱和像素</Text>
                                            <Text className="font-semibold text-slate-800">{formatCount(bothSaturatedCount)}</Text>
                                            <Text className="mt-1 block text-[11px] text-slate-400">{formatRatio(bothSaturatedRatio)}</Text>
                                        </Card>
                                    </div>

                                    <div className="grid gap-2 sm:grid-cols-2 xl:grid-cols-3">
                                        <div className="rounded-lg bg-slate-50 p-3">
                                            <Text className="block text-xs text-slate-500">HG/LG增益比</Text>
                                            <Text className="font-semibold text-slate-800">
                                                {currentFrame.hdrGainRatio?.toFixed(4) ?? "-"}
                                            </Text>
                                            <Text className="mt-1 block text-[11px] text-slate-400">
                                                {gainRatioFallback ? "样本不足，使用默认/钳制值" : "来自共同线性区中位数估计"}
                                            </Text>
                                        </div>
                                        <div className="rounded-lg bg-slate-50 p-3">
                                            <Text className="block text-xs text-slate-500">增益估计样本</Text>
                                            <Text className="font-semibold text-slate-800">
                                                {formatCount(gainSampleCount)} / 最少 {formatCount(gainSampleMinimum)}
                                            </Text>
                                            <Text className="mt-1 block text-[11px] text-slate-400">
                                                占整图 {formatRatio(gainSampleRatio)}
                                            </Text>
                                        </div>
                                        <div className="rounded-lg bg-slate-50 p-3">
                                            <Text className="block text-xs text-slate-500">HG饱和比例</Text>
                                            <Text className="font-semibold text-slate-800">
                                                {formatRatio(hgSaturatedRatio, 4)}
                                            </Text>
                                            <Text className="mt-1 block text-[11px] text-slate-400">
                                                HG局部饱和不一定坏，表示需要LG接管高亮区域
                                            </Text>
                                        </div>
                                        <div className="rounded-lg bg-slate-50 p-3">
                                            <Text className="block text-xs text-slate-500">LG饱和比例</Text>
                                            <Text className="font-semibold text-slate-800">
                                                {formatRatio(lgSaturatedRatio, 4)}
                                            </Text>
                                            <Text className="mt-1 block text-[11px] text-slate-400">
                                                参考警戒线 {formatRatio(warningLgSaturatedRatio, 3)}
                                            </Text>
                                        </div>
                                        <div className="rounded-lg bg-slate-50 p-3">
                                            <Text className="block text-xs text-slate-500">LG补偿覆盖比例</Text>
                                            <Text className="font-semibold text-slate-800">
                                                {formatRatio(lgCompensationRatio)}
                                            </Text>
                                            <Text className="mt-1 block text-[11px] text-slate-400">
                                                LG使用 + 过渡混合区域
                                            </Text>
                                        </div>
                                        <div className="rounded-lg bg-slate-50 p-3">
                                            <Text className="block text-xs text-slate-500">双饱和参考线</Text>
                                            <Text className="font-semibold text-slate-800">
                                                警告 {formatRatio(warningBothSaturatedRatio, 3)} / 失败 {formatRatio(failBothSaturatedRatio, 2)}
                                            </Text>
                                            <Text className="mt-1 block text-[11px] text-slate-400">
                                                双饱和表示HG和LG都无法恢复真实强度
                                            </Text>
                                        </div>
                                    </div>

                                    <Text className="block text-xs leading-5 text-slate-500">
                                        融合规则：HG DN 小于 {formatNumber(blendStartHgDn, 0)} 时主要使用HG；
                                        大于等于 {formatNumber(blendEndHgDn, 0)} 时使用增益缩放后的LG；
                                        中间区间按HG亮度从HG平滑过渡到LG。
                                    </Text>
                                </div>
                            </Card>

                            <div className="grid gap-4 xl:grid-cols-[minmax(50%,1.1fr)_minmax(320px,0.9fr)] xl:items-start">
                                <ImageVersionPreview
                                    frame={currentFrame}
                                    defaultVersion={
                                        qualityViewMode === "after" && currentFrame.processedQualityStatus
                                            ? "processed"
                                            : currentFrame.calibratedImageDataUrl
                                              ? "calibrated"
                                              : "raw"
                                    }
                                    emptyText="暂无HDR融合主图"
                                    imageAreaClassName="min-h-[360px] xl:min-h-[420px]"
                                    rawLabel="融合主图"
                                    rawDescription="HG/LG融合后保存的主图，后续质量分析、图像处理和光谱提取均基于它继续执行。"
                                />

                                <div className="space-y-3">
                                    <div className="grid gap-2 rounded-lg border border-slate-200 bg-slate-50 p-3 text-sm text-slate-600 sm:grid-cols-2 xl:grid-cols-1 2xl:grid-cols-2">
                                        <div>
                                            <Text className="block text-xs text-slate-500">采集类型</Text>
                                            <Tag color="purple" className="mt-1">HDR融合图像</Tag>
                                        </div>
                                        <div>
                                            <Text className="block text-xs text-slate-500">尺寸</Text>
                                            <span className="font-medium text-slate-800">
                                                {currentFrame.width} x {currentFrame.height}
                                            </span>
                                        </div>
                                        <div>
                                            <Text className="block text-xs text-slate-500">8-bit预览</Text>
                                            <span className="font-medium text-slate-800">{currentFrame.raw8Length} bytes</span>
                                        </div>
                                        <div>
                                            <Text className="block text-xs text-slate-500">接收时间</Text>
                                            <span className="font-medium text-slate-800">
                                                {new Date(currentFrame.timestamp).toLocaleString("zh-CN")}
                                            </span>
                                        </div>
                                        <div>
                                            <Text className="block text-xs text-slate-500">质量状态</Text>
                                            <Tag color={qualityColor(currentFrame.qualityStatus)} className="mt-1">
                                                {currentFrame.qualityStatus || "NOT_EVALUATED"}
                                            </Tag>
                                        </div>
                                        <div>
                                            <Text className="block text-xs text-slate-500">图像处理</Text>
                                            <Tag color={getProcessingStateColor(currentFrame)} className="mt-1">
                                                {getProcessingStateText(currentFrame)}
                                            </Tag>
                                        </div>
                                        <div className="sm:col-span-2 xl:col-span-1 2xl:col-span-2">
                                            <Text className="mb-1 block text-xs text-slate-500">融合主图RAW16像素</Text>
                                            <Space wrap size={6}>
                                                <ImagePixelDataViewer
                                                    frame={currentFrame}
                                                    defaultSource={
                                                        qualityViewMode === "after" && currentFrame.processedQualityStatus
                                                            ? "PROCESSED"
                                                            : currentFrame.calibratedImageDataUrl
                                                              ? "CALIBRATED"
                                                              : "ORIGINAL"
                                                    }
                                                    triggerLabel="查看融合主图RAW16像素"
                                                />
                                                <FpgaPayloadDataViewer
                                                    frame={currentFrame}
                                                    triggerLabel="查看原始HDR payload映射"
                                                />
                                            </Space>
                                        </div>
                                    </div>

                                    {processingDisabledReason && (
                                        <div className="rounded-md border border-orange-200 bg-orange-50 px-3 py-2 text-xs leading-relaxed text-orange-700">
                                            处理限制：{processingDisabledReason}
                                        </div>
                                    )}
                                </div>
                            </div>

                            {(processingDisabledReason || spectrumDisabledReason) && (
                                <Alert
                                    type="info"
                                    showIcon
                                    message="当前可执行操作提示"
                                    description={[
                                        processingDisabledReason ? `处理：${processingDisabledReason}` : null,
                                        spectrumDisabledReason ? `光谱提取：${spectrumDisabledReason}` : null,
                                    ].filter(Boolean).join("；")}
                                />
                            )}

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

                                <div className="space-y-4">
                                    <div className="flex flex-wrap gap-2">
                                        <Button
                                            type={qualityViewMode === "before" ? "primary" : "default"}
                                            onClick={() => setQualityViewMode("before")}
                                        >
                                            {currentFrame.calibratedImageDataUrl ? "处理前（校准后）" : "处理前"}
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
                                                    <Tag color={dispositionColor(currentFrame.dispositionStatus)} className="m-0">
                                                        {currentFrame.dispositionStatus || "MANUAL_REVIEW"}
                                                    </Tag>
                                                    <Tag color={currentFrame.usableForSpectral ? "green" : "orange"} className="m-0">
                                                        {currentFrame.usableForSpectral ? "可进入光谱提取" : "暂不进入光谱提取"}
                                                    </Tag>
                                                </div>
                                            </div>
                                            <div className="text-sm leading-relaxed text-slate-600">
                                                {currentFrame.dispositionMessage || "暂无处置建议，请先完成质量分析。"}
                                            </div>
                                            {processingDisabledReason && (
                                                <div className="mt-2 rounded-md border border-orange-200 bg-orange-50 px-3 py-2 text-xs leading-relaxed text-orange-700">
                                                    {autoProcess ? "自动处理提示" : "处理限制"}：{processingDisabledReason}
                                                </div>
                                            )}
                                            {currentFrame.recommendedActions.length > 0 && (
                                                <div className="mt-3 grid gap-2 md:grid-cols-2 xl:grid-cols-3">
                                                    {currentFrame.recommendedActions.map((action) => (
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

                                    {qualityViewMode === "after" && currentFrame.processingStatus && (
                                        <div className="rounded-lg border border-emerald-200 bg-emerald-50 p-3">
                                            <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
                                                <Text className="text-sm font-medium text-emerald-700">处理后复检结果</Text>
                                                <div className="flex flex-wrap gap-2">
                                                    <Tag color={processingColor(currentFrame.processingStatus)} className="m-0">
                                                        {processingLabel(currentFrame.processingStatus)}
                                                    </Tag>
                                                    {currentFrame.processedQualityStatus && (
                                                        <Tag color={qualityColor(currentFrame.processedQualityStatus)} className="m-0">
                                                            复检 {currentFrame.processedQualityStatus}
                                                        </Tag>
                                                    )}
                                                    {typeof currentFrame.processedUsableForSpectral === "boolean" && (
                                                        <Tag
                                                            color={currentFrame.processedUsableForSpectral ? "green" : "orange"}
                                                            className="m-0"
                                                        >
                                                            {currentFrame.processedUsableForSpectral
                                                                ? "处理后可进入光谱提取"
                                                                : "处理后仍需复核"}
                                                        </Tag>
                                                    )}
                                                    {currentFrame.processedDispositionStatus && (
                                                        <Tag
                                                            color={dispositionColor(currentFrame.processedDispositionStatus)}
                                                            className="m-0"
                                                        >
                                                            处置 {currentFrame.processedDispositionStatus}
                                                        </Tag>
                                                    )}
                                                </div>
                                            </div>
                                            <div className="text-sm leading-relaxed text-slate-600">
                                                {currentFrame.processedDispositionMessage ||
                                                    currentFrame.processingMessage ||
                                                    "已完成当前阶段可执行的图像处理。"}
                                            </div>
                                            {currentFrame.executedProcessingActions.length > 0 && (
                                                <div className="mt-3 grid gap-2 md:grid-cols-2 xl:grid-cols-3">
                                                    {currentFrame.executedProcessingActions.map((action) => (
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
                                            {qualityViewMode === "before" && currentFrame.calibratedImageDataUrl
                                                ? "校准后完整质量指标"
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
                            </Card>

                            <Alert
                                type="info"
                                showIcon
                                message="HDR融合说明"
                                description={
                                    storagePolicy
                                        ? `${storagePolicy}；当前主图仍进入RAW16_LOW12质量分析流程，HG/LG原始平面已单独保存。`
                                        : "当前主图为HDR融合结果；HG/LG输入平面已单独保存，便于后续升级扩展动态范围定量分析。"
                                }
                            />

                            <Card size="small" className="border border-gray-200 bg-white shadow-sm">
                                <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
                                    <div>
                                        <Title level={5} className="!mb-0 flex items-center gap-2 !text-slate-800">
                                            <Activity size={18} />
                                            当前帧一维光谱提取
                                        </Title>
                                        <Text className="text-xs text-slate-500">
                                            同一张HDR融合主图只保留最新一条像素域光谱；改变参数后重新提取会覆盖原记录
                                        </Text>
                                    </div>
                                    <Tag color={canExtractSpectrum(currentFrame) ? "green" : "orange"}>
                                        {canExtractSpectrum(currentFrame) ? "可提取" : "等待PASS图像"}
                                    </Tag>
                                </div>

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
                                                    max={key.startsWith("x") ? currentFrame.width : currentFrame.height}
                                                    className="w-full"
                                                    placeholder={
                                                        key === "xStart"
                                                            ? "0"
                                                            : key === "xEnd"
                                                              ? String(currentFrame.width)
                                                              : key === "yStart"
                                                                ? "0"
                                                                : String(currentFrame.height)
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
                                            disabled={Boolean(spectrumDisabledReason) || loadingAction !== null}
                                            title={spectrumDisabledReason || "提取一维像素域光谱"}
                                            onClick={handleExtractSpectrum}
                                        >
                                            提取一维光谱
                                        </Button>
                                    </div>

                                    {spectrumDisabledReason && (
                                        <div className="rounded-md border border-orange-200 bg-orange-50 px-3 py-2 text-xs leading-relaxed text-orange-700">
                                            {spectrumDisabledReason}
                                        </div>
                                    )}

                                    {spectrumResult ? (
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
                                                <div>最小强度：{formatNumber(spectrumResult.intensityMin)}</div>
                                                <div>最大强度：{formatNumber(spectrumResult.intensityMax)}</div>
                                                <div>平均强度：{formatNumber(spectrumResult.intensityMean)}</div>
                                            </div>
                                            <div className="text-xs leading-relaxed text-slate-500">
                                                {spectrumResult.summaryMessage}
                                            </div>
                                        </div>
                                    ) : (
                                        <Empty
                                            image={Empty.PRESENTED_IMAGE_SIMPLE}
                                            description="当前HDR融合主图还没有一维光谱结果。"
                                        />
                                    )}
                                </div>
                            </Card>
                        </div>
                    ) : (
                        <div className="flex min-h-[420px] items-center justify-center rounded-xl bg-slate-950">
                            {loadingLatestFrame ? (
                                <Spin tip="正在加载最新HDR图像" />
                            ) : (
                                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={<span className="text-slate-400">暂无HDR图像</span>} />
                            )}
                        </div>
                    )}
                </Card>

            </div>

            <Modal
                title={zoomItem?.label ?? "HDR图像预览"}
                open={Boolean(zoomItem)}
                onCancel={() => setZoomItem(null)}
                footer={<Button type="primary" onClick={() => setZoomItem(null)}>关闭</Button>}
                width="92vw"
                style={{ maxWidth: 1400, top: 24 }}
                destroyOnClose
            >
                <div className="space-y-3">
                    <div className="h-[72vh] overflow-auto rounded-lg bg-slate-950 p-4 text-center">
                        {zoomItem && (
                            <img
                                src={zoomItem.url}
                                alt={zoomItem.label}
                                className="inline-block max-h-full max-w-full object-contain align-middle"
                            />
                        )}
                    </div>
                    {zoomItem && <Text className="block text-xs text-slate-500">{zoomItem.description}</Text>}
                </div>
            </Modal>
        </div>
    );
};

export default HdrCapturePage;
