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

const getQualityThreshold = (
    frame: ImageFrameRecord | null,
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

const getQualityDecisionReasons = (frame: ImageFrameRecord | null): string[] => {
    const reasons = frame?.qualityDetails?.decisionReasons;
    if (Array.isArray(reasons)) {
        return reasons.filter((item): item is string => typeof item === "string");
    }
    return [];
};

const buildQualityMetricExplanations = (frame: ImageFrameRecord | null): QualityMetricExplanation[] => {
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

const SpectralDataManagementPage: React.FC = () => {
    const { imageHistory } = useJNIStore();
    const [selectedFrame, setSelectedFrame] = useState<ImageFrameRecord | null>(null);
    const [previewVisible, setPreviewVisible] = useState(false);
    const [loading, setLoading] = useState(false);

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

    const selectedQualityMetricExplanations = buildQualityMetricExplanations(selectedFrame);
    const selectedProblemQualityMetrics = selectedQualityMetricExplanations.filter(
        (metric) => metric.status === "WARNING" || metric.status === "FAIL",
    );
    const selectedQualityDecisionReasons = getQualityDecisionReasons(selectedFrame);

    const columns: ColumnsType<ImageFrameRecord> = [
        {
            title: "预览",
            dataIndex: "imageDataUrl",
            key: "imageDataUrl",
            width: 120,
            render: (imageDataUrl: string) => (
                <div className="flex h-16 w-24 items-center justify-center overflow-hidden rounded bg-slate-950">
                    {imageDataUrl ? (
                        <img src={imageDataUrl} alt="图像帧缩略图" className="h-full w-full object-contain" />
                    ) : (
                        <ImageIcon size={22} className="text-slate-400" />
                    )}
                </div>
            ),
        },
        {
            title: "图像信息",
            key: "frame",
            render: (_, record) => (
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
                    <Tag color={qualityColor(record.qualityStatus)} className="mt-2">
                        质量 {record.qualityStatus || "NOT_EVALUATED"}
                    </Tag>
                    <Tag color={dispositionColor(record.dispositionStatus)} className="mt-2">
                        处置 {record.dispositionStatus || "MANUAL_REVIEW"}
                    </Tag>
                </div>
            ),
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
                            <div className="flex h-[52vh] min-h-[360px] max-h-[560px] items-center justify-center rounded-md bg-slate-950">
                                {selectedFrame.imageDataUrl ? (
                                    <img
                                        src={selectedFrame.imageDataUrl}
                                        alt="图像帧预览"
                                        className="h-full max-w-full object-contain"
                                    />
                                ) : (
                                    <ImageIcon size={42} className="text-slate-500" />
                                )}
                            </div>

                            <div className="grid gap-2 rounded-md bg-gray-50 p-3 sm:grid-cols-2 lg:grid-cols-5 xl:grid-cols-2 2xl:grid-cols-5">
                                <div className="rounded bg-white px-2 py-2">
                                    <Text className="text-xs text-slate-500">尺寸</Text>
                                    <div className="font-semibold text-slate-800">
                                        {selectedFrame.width} x {selectedFrame.height}
                                    </div>
                                </div>
                                <div className="rounded bg-white px-2 py-2">
                                    <Text className="text-xs text-slate-500">像素格式</Text>
                                    <div className="font-semibold text-slate-800">{selectedFrame.pixelFormat}</div>
                                </div>
                                <div className="rounded bg-white px-2 py-2">
                                    <Text className="text-xs text-slate-500">Payload</Text>
                                    <div className="font-semibold text-slate-800">
                                        {(selectedFrame.payloadLength / 1024).toFixed(2)} KB
                                    </div>
                                </div>
                                <div className="rounded bg-white px-2 py-2">
                                    <Text className="text-xs text-slate-500">完整性</Text>
                                    <div className="font-semibold text-slate-800">
                                        {selectedFrame.integrityResultCode || "UNKNOWN"}
                                    </div>
                                </div>
                                <div className="rounded bg-white px-2 py-2">
                                    <Text className="text-xs text-slate-500">质量状态</Text>
                                    <div>
                                        <Tag color={qualityColor(selectedFrame.qualityStatus)} className="mt-1">
                                            {selectedFrame.qualityStatus || "NOT_EVALUATED"}
                                        </Tag>
                                    </div>
                                </div>
                                <div className="rounded bg-white px-2 py-2">
                                    <Text className="text-xs text-slate-500">灰度 min/max/mean</Text>
                                    <div className="font-semibold text-slate-800">
                                        {selectedFrame.pixelMin ?? "-"} / {selectedFrame.pixelMax ?? "-"} /{" "}
                                        {formatNullableNumber(selectedFrame.pixelMean)}
                                    </div>
                                </div>
                                <div className="rounded bg-white px-2 py-2">
                                    <Text className="text-xs text-slate-500">标准差</Text>
                                    <div className="font-semibold text-slate-800">
                                        {formatNullableNumber(selectedFrame.pixelStddev)}
                                    </div>
                                </div>
                                <div className="rounded bg-white px-2 py-2">
                                    <Text className="text-xs text-slate-500">黑/饱和像素</Text>
                                    <div className="font-semibold text-slate-800">
                                        {formatNullableRatio(selectedFrame.blackPixelRatio)} /{" "}
                                        {formatNullableRatio(selectedFrame.saturationPixelRatio)}
                                    </div>
                                </div>
                                <div className="rounded bg-white px-2 py-2">
                                    <Text className="text-xs text-slate-500">异常行/列</Text>
                                    <div className="font-semibold text-slate-800">
                                        {selectedFrame.abnormalRowCount ?? "-"} /{" "}
                                        {selectedFrame.abnormalColumnCount ?? "-"}
                                    </div>
                                </div>
                                <div className="rounded bg-white px-2 py-2">
                                    <Text className="text-xs text-slate-500">坏点</Text>
                                    <div className="font-semibold text-slate-800">
                                        {selectedFrame.badPixelCount ?? "-"}
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
                                <Tag color={qualityColor(selectedFrame.qualityStatus)}>
                                    {selectedFrame.qualityStatus || "NOT_EVALUATED"}
                                </Tag>
                            </div>

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

                            {selectedFrame.qualityStatus !== "PASS" && (
                                <div className="mb-4 rounded-lg border border-orange-200 bg-orange-50 p-3">
                                    <Text className="block text-sm font-medium text-orange-700">
                                        导致当前状态不是 PASS 的指标
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
                                            {selectedFrame.qualitySummaryMessage ||
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
