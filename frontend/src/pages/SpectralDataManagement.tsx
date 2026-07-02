import React, { useEffect, useState } from "react";
import { Button, Card, Empty, Modal, Space, Spin, Table, Tag, Typography } from "antd";
import type { ColumnsType } from "antd/es/table";
import { Activity, Clock, Database, Eye, Image as ImageIcon, RefreshCw, Trash2 } from "lucide-react";
import { toast } from "sonner";

import { jniBridgeService } from "../service/jniBridgeService";
import { useJNIStore } from "../store/jniStore";
import type { ImageFrameRecord } from "../types/jni";

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

const SpectralDataManagementPage: React.FC = () => {
    const { imageHistory } = useJNIStore();
    const [selectedFrame, setSelectedFrame] = useState<ImageFrameRecord | null>(null);
    const [previewVisible, setPreviewVisible] = useState(false);
    const [loading, setLoading] = useState(false);
    const [processingImageId, setProcessingImageId] = useState<number | null>(null);
    const [qualityViewMode, setQualityViewMode] = useState<QualityViewMode>("before");

    /**
     * 历史图片的唯一数据源是后端数据库和服务器文件系统。
     * 页面刷新后重新请求数据库，不再读取浏览器localStorage中的PNG data URL。
     */
    const loadHistory = async () => {
        setLoading(true);
        try {
            await jniBridgeService.loadImageHistory();
        } catch (error: any) {
            toast.error(error?.message || "加载数据库图片失败");
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        loadHistory();
    }, []);

    useEffect(() => {
        setQualityViewMode(buildProcessedQualitySource(selectedFrame) ? "after" : "before");
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
                if (selectedFrame?.id === record.id) {
                    setSelectedFrame(null);
                    setPreviewVisible(false);
                }
                toast.success("数据库图片已删除");
            },
        });
    };

    const handlePreview = (record: ImageFrameRecord) => {
        setSelectedFrame(record);
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

    const handleClearAll = () => {
        Modal.confirm({
            title: "确认清空",
            content: "确定要清空当前用户的所有数据库图像和服务器文件吗？此操作不可恢复。",
            okText: "确认清空",
            cancelText: "取消",
            okButtonProps: { danger: true },
            onOk: async () => {
                await jniBridgeService.clearImages();
                setSelectedFrame(null);
                setPreviewVisible(false);
                toast.success("数据库历史已清空");
            },
        });
    };

    const selectedOriginalQualitySource = buildOriginalQualitySource(selectedFrame);
    const selectedProcessedQualitySource = buildProcessedQualitySource(selectedFrame);
    const selectedActiveQualitySource = qualityViewMode === "after" && selectedProcessedQualitySource
        ? selectedProcessedQualitySource
        : selectedOriginalQualitySource;
    const selectedSummaryUsesProcessed = qualityViewMode === "after" && Boolean(selectedProcessedQualitySource);
    const selectedSummaryQualityStatus = selectedActiveQualitySource?.qualityStatus ?? null;
    const selectedSummaryDispositionStatus = selectedSummaryUsesProcessed
        ? selectedFrame?.processedDispositionStatus ?? null
        : selectedFrame?.dispositionStatus ?? null;
    const selectedSummaryScopeLabel = selectedSummaryUsesProcessed ? "复检" : "原图";
    const selectedCounterpartScopeLabel = selectedSummaryUsesProcessed ? "原图" : "复检";
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

    const columns: ColumnsType<ImageFrameRecord> = [
        {
            title: "预览",
            key: "imageDataUrl",
            width: 120,
            render: (_, record) => {
                const previewUrl = record.processedImageDataUrl || record.imageDataUrl;
                return (
                    <div className="space-y-1">
                        <div className="flex h-16 w-24 items-center justify-center overflow-hidden rounded bg-slate-950">
                            {previewUrl ? (
                                <img src={previewUrl} alt="图像帧缩略图" className="h-full w-full object-contain" />
                            ) : (
                                <ImageIcon size={22} className="text-slate-400" />
                            )}
                        </div>
                        {record.processedImageDataUrl && (
                            <Tag color="green" className="m-0 text-[11px]">
                                处理后预览
                            </Tag>
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
                return (
                    <div>
                        <div className="font-medium text-slate-800">
                            {record.width} x {record.height}
                        </div>
                        <div className="mt-1 text-xs text-slate-500">
                            {record.pixelFormat} · Payload {(record.payloadLength / 1024).toFixed(2)} KB
                        </div>
                        <Tag color={record.integrityPassed ? "green" : "red"} className="mt-2">
                            {record.integrityResultCode || "UNKNOWN"}
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
                                图像帧管理
                            </Title>
                            <Text className="text-sm text-slate-500">
                                查看PostgreSQL记录和服务器保存的原始光谱图像
                            </Text>
                        </div>
                    </div>

                    <div className="flex items-center gap-3">
                        <Button icon={<RefreshCw size={16} />} onClick={loadHistory} loading={loading}>
                            刷新
                        </Button>
                        <Tag color="blue" className="px-3 py-1 text-sm">
                            共 {imageHistory.length} 帧
                        </Tag>
                        {imageHistory.length > 0 && (
                            <Button danger icon={<Trash2 size={16} />} onClick={handleClearAll}>
                                清空全部
                            </Button>
                        )}
                    </div>
                </div>

                {loading && imageHistory.length === 0 ? (
                    <div className="flex justify-center py-16">
                        <Spin tip="正在加载数据库图片" />
                    </div>
                ) : imageHistory.length === 0 ? (
                    <Empty
                        image={Empty.PRESENTED_IMAGE_SIMPLE}
                        description={
                            <div className="text-slate-500">
                                <div className="mb-2">数据库中暂无历史图像帧</div>
                                <div className="text-sm">请先在“光谱桥接控制”页面获取一帧图片</div>
                            </div>
                        }
                        className="py-12"
                    />
                ) : (
                    <Table
                        columns={columns}
                        dataSource={imageHistory}
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
                    </div>
                }
                open={previewVisible}
                onCancel={() => setPreviewVisible(false)}
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
                    <Button key="close" onClick={() => setPreviewVisible(false)}>
                        关闭
                    </Button>,
                ]}
                width="96vw"
                style={{ maxWidth: 1480, top: 24 }}
            >
                {selectedFrame && (
                    <div className="grid gap-4 xl:grid-cols-[minmax(0,1.05fr)_minmax(520px,0.95fr)]">
                        <div className="space-y-3">
                            <div className="h-[52vh] min-h-[360px] max-h-[560px] overflow-hidden rounded-md bg-slate-950">
                                {selectedFrame.imageDataUrl ? (
                                    <div
                                        className={`grid h-full gap-px bg-slate-800 ${
                                            selectedFrame.processedImageDataUrl ? "md:grid-cols-2" : "grid-cols-1"
                                        }`}
                                    >
                                        <div className="flex h-full min-h-[360px] flex-col bg-slate-950">
                                            <div className="flex flex-1 items-center justify-center p-2">
                                                <img
                                                    src={selectedFrame.imageDataUrl}
                                                    alt="图像帧原图预览"
                                                    className="h-full max-w-full object-contain"
                                                />
                                            </div>
                                            <div className="border-t border-slate-800 px-3 py-2 text-center text-xs font-medium text-slate-300">
                                                原图
                                            </div>
                                        </div>
                                        {selectedFrame.processedImageDataUrl && (
                                            <div className="flex h-full min-h-[360px] flex-col bg-slate-950">
                                                <div className="flex flex-1 items-center justify-center p-2">
                                                    <img
                                                        src={selectedFrame.processedImageDataUrl}
                                                        alt="图像帧处理后预览"
                                                        className="h-full max-w-full object-contain"
                                                    />
                                                </div>
                                                <div className="border-t border-slate-800 px-3 py-2 text-center text-xs font-medium text-emerald-300">
                                                    处理后
                                                </div>
                                            </div>
                                        )}
                                    </div>
                                ) : (
                                    <div className="flex h-full items-center justify-center">
                                        <ImageIcon size={42} className="text-slate-500" />
                                    </div>
                                )}
                            </div>

                            <div className="grid gap-2 rounded-md bg-gray-50 p-3 sm:grid-cols-2 lg:grid-cols-5 xl:grid-cols-2 2xl:grid-cols-5">
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
                                    <Text className="text-xs text-slate-500">完整性</Text>
                                    <div className="break-all font-semibold text-slate-800">
                                        {selectedFrame.integrityResultCode || "UNKNOWN"}
                                    </div>
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
                                    处理前
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
        </div>
    );
};

export default SpectralDataManagementPage;
