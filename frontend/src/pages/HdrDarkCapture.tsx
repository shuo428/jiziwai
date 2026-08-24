import React, { useEffect, useMemo, useState } from "react";
import { Alert, Button, Card, Empty, Modal, Space, Spin, Table, Tag, Typography } from "antd";
import type { ColumnsType } from "antd/es/table";
import { Camera, Database, Moon, RefreshCw, Trash2 } from "lucide-react";
import { toast } from "sonner";

import FpgaPayloadDataViewer from "../components/FpgaPayloadDataViewer";
import ImagePixelDataViewer from "../components/ImagePixelDataViewer";
import ImageVersionPreview from "../components/ImageVersionPreview";
import { jniBridgeService } from "../service/jniBridgeService";
import { useJNIStore } from "../store/jniStore";
import type { ImageFrameRecord } from "../types/jni";

const { Title, Text } = Typography;

type PlanePreviewItem = {
    key: string;
    label: string;
    url: string;
    tagColor: string;
    description: string;
};

const numberFromDetails = (details: Record<string, unknown> | null | undefined, key: string): number | null => {
    const value = details?.[key];
    return typeof value === "number" ? value : null;
};

const formatNumber = (value: number | null | undefined, digits = 2): string =>
    typeof value === "number" && Number.isFinite(value) ? value.toFixed(digits) : "-";

const formatRatio = (value: number | null | undefined, digits = 2): string =>
    typeof value === "number" && Number.isFinite(value) ? `${(value * 100).toFixed(digits)}%` : "-";

const buildHdrDarkPlanePreviewItems = (frame: ImageFrameRecord | null): PlanePreviewItem[] => {
    if (!frame) {
        return [];
    }
    const items: PlanePreviewItem[] = [];
    if (frame.hgImageDataUrl) {
        items.push({
            key: "hg-dark",
            label: "HG暗场平面",
            url: frame.hgImageDataUrl,
            tagColor: "geekblue",
            description: "高增益暗场平面，native 已按当前读出顺序重排为正常行列坐标。",
        });
    }
    if (frame.lgImageDataUrl) {
        items.push({
            key: "lg-dark",
            label: "LG暗场平面",
            url: frame.lgImageDataUrl,
            tagColor: "purple",
            description: "低增益暗场平面，后续 HDR 校准包应与 HG 暗场分开生成参考图。",
        });
    }
    return items;
};

const HdrDarkCapturePage: React.FC = () => {
    const { bridgeState, actions } = useJNIStore();
    const [capturing, setCapturing] = useState(false);
    const [loadingHistory, setLoadingHistory] = useState(false);
    const [currentFrame, setCurrentFrame] = useState<ImageFrameRecord | null>(null);
    const [history, setHistory] = useState<ImageFrameRecord[]>([]);
    const [previewFrame, setPreviewFrame] = useState<ImageFrameRecord | null>(null);
    const [zoomItem, setZoomItem] = useState<PlanePreviewItem | null>(null);

    useEffect(() => {
        jniBridgeService.initialize().catch((err: Error) => {
            actions.setError(err.message);
        });
    }, [actions]);

    const loadHistory = async () => {
        setLoadingHistory(true);
        try {
            const frames = await jniBridgeService.loadHdrDarkImageHistory();
            setHistory(frames);
            setCurrentFrame((previous) => {
                if (!previous) {
                    return frames[0] ?? null;
                }
                return frames.some((frame) => frame.id === previous.id) ? previous : frames[0] ?? null;
            });
        } catch (error) {
            toast.error(error instanceof Error ? error.message : "加载HDR暗场历史失败");
        } finally {
            setLoadingHistory(false);
        }
    };

    useEffect(() => {
        void loadHistory();
    }, []);

    const handleCapture = async () => {
        setCapturing(true);
        try {
            const frame = await jniBridgeService.triggerOnceAndWaitForFrame({
                autoProcess: false,
                captureScene: "HDR_DARK",
            });
            if (frame.captureScene !== "HDR_DARK") {
                toast.warning(`收到的采集场景为 ${frame.captureScene}，不是 HDR_DARK，请检查前后端模式约定。`);
            }
            setCurrentFrame(frame);
            setHistory((frames) => [frame, ...frames.filter((item) => item.id !== frame.id)].slice(0, 50));
            toast.success("HDR暗场双平面样本采集完成");
            void loadHistory();
        } catch (error) {
            toast.error(error instanceof Error ? error.message : "HDR暗场采集失败");
        } finally {
            setCapturing(false);
        }
    };

    const handleDelete = (record: ImageFrameRecord) => {
        Modal.confirm({
            title: "确认删除HDR暗场样本",
            content: "将删除该次HDR暗场采集的数据库记录、HG/LG平面、诊断主图和原始payload文件。此操作不可恢复。",
            okText: "确认删除",
            cancelText: "取消",
            okButtonProps: { danger: true },
            onOk: async () => {
                await jniBridgeService.deleteImage(record.id);
                setHistory((frames) => frames.filter((item) => item.id !== record.id));
                if (currentFrame?.id === record.id) {
                    setCurrentFrame(null);
                }
                if (previewFrame?.id === record.id) {
                    setPreviewFrame(null);
                }
                toast.success("HDR暗场样本已删除");
                void loadHistory();
            },
        });
    };

    const activePlaneItems = useMemo(() => buildHdrDarkPlanePreviewItems(currentFrame), [currentFrame]);
    const previewPlaneItems = useMemo(() => buildHdrDarkPlanePreviewItems(previewFrame), [previewFrame]);
    const activeDetails = currentFrame?.hdrFusionDetails ?? null;
    const hgMean = numberFromDetails(activeDetails, "hgDarkMeanDn");
    const lgMean = numberFromDetails(activeDetails, "lgDarkMeanDn");
    const hgStddev = numberFromDetails(activeDetails, "hgDarkStddevDn");
    const lgStddev = numberFromDetails(activeDetails, "lgDarkStddevDn");
    const hgBrightRatio = numberFromDetails(activeDetails, "hgBrightReferenceRatio");
    const lgBrightRatio = numberFromDetails(activeDetails, "lgBrightReferenceRatio");
    const hgDominantRatio = numberFromDetails(activeDetails, "hgDominantRatio");
    const lgDominantRatio = numberFromDetails(activeDetails, "lgDominantRatio");

    const columns: ColumnsType<ImageFrameRecord> = [
        {
            title: "样本",
            dataIndex: "id",
            key: "sample",
            render: (_: unknown, record) => (
                <div className="flex items-center gap-3">
                    <div className="flex h-14 w-20 items-center justify-center overflow-hidden rounded bg-slate-950">
                        {record.imageDataUrl ? (
                            <img src={record.imageDataUrl} alt="HDR暗场诊断图" className="h-full w-full object-contain" />
                        ) : (
                            <span className="text-xs text-slate-400">无预览</span>
                        )}
                    </div>
                    <div>
                        <div className="font-medium text-slate-800">HDR暗场 #{record.id}</div>
                        <div className="text-xs text-slate-500">{new Date(record.timestamp).toLocaleString("zh-CN")}</div>
                    </div>
                </div>
            ),
        },
        {
            title: "规格",
            key: "spec",
            render: (_: unknown, record) => (
                <Space direction="vertical" size={2}>
                    <Tag color="cyan" className="m-0">
                        {record.width}×{record.height}
                    </Tag>
                    <Tag color="blue" className="m-0">
                        {record.readoutOrder || "未记录"}
                    </Tag>
                </Space>
            ),
        },
        {
            title: "HG/LG暗场均值",
            key: "mean",
            render: (_: unknown, record) => {
                const details = record.hdrFusionDetails ?? null;
                return (
                    <span className="font-mono text-sm text-slate-700">
                        {formatNumber(numberFromDetails(details, "hgDarkMeanDn"))} /{" "}
                        {formatNumber(numberFromDetails(details, "lgDarkMeanDn"))} DN
                    </span>
                );
            },
        },
        {
            title: "完整性",
            key: "integrity",
            render: (_: unknown, record) => (
                <Tag color={record.integrityPassed ? "green" : "red"}>
                    {record.integrityResultCode || (record.integrityPassed ? "OK" : "UNKNOWN")}
                </Tag>
            ),
        },
        {
            title: "操作",
            key: "actions",
            align: "right",
            render: (_: unknown, record) => (
                <Space wrap>
                    <Button size="small" onClick={() => setPreviewFrame(record)}>
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
        <div className="space-y-5 p-6">
            <div className="flex flex-wrap items-start justify-between gap-4">
                <div>
                    <Title level={3} className="!mb-1 !text-slate-800">
                        HDR 暗场双平面采集
                    </Title>
                    <Text className="text-slate-500">
                        一次触发接收 HG_DARK + LG_DARK 双平面；native 负责拆分和重排，Java 保存平面并生成暗场诊断主图。
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

            <Alert
                type="info"
                showIcon
                message="当前模块只保存 HDR 暗场样本"
                description="HDR暗场样本不会套用当前普通校准包，也不会执行图像处理或光谱提取。后续HDR校准包应把HG暗场、LG暗场与HDR平场分别组合生成参考图。"
            />

            <Card className="border border-slate-200 bg-white shadow-sm">
                <div className="flex flex-wrap items-center justify-between gap-4">
                    <div>
                        <Title level={5} className="!mb-1 flex items-center gap-2 !text-slate-800">
                            <Moon size={18} />
                            HDR 暗场触发
                        </Title>
                        <Text className="text-sm text-slate-500">
                            请遮光后采集；当前期望 FPGA 返回 payload = width × height × 2 × 2，顺序为 HG 暗场完整帧 + LG 暗场完整帧。
                        </Text>
                    </div>
                    <Button
                        type="primary"
                        icon={<Camera size={16} />}
                        disabled={!bridgeState.connected}
                        loading={capturing}
                        onClick={handleCapture}
                    >
                        获取一帧HDR暗场
                    </Button>
                </div>
            </Card>

            {!bridgeState.connected && (
                <Alert
                    type="warning"
                    showIcon
                    message="设备未连接"
                    description="请先在“设备总览”中连接 FPGA/CMOS 图像通道，再执行 HDR 暗场采集。"
                />
            )}

            <Card className="border border-slate-200 bg-white shadow-sm">
                <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
                    <Title level={5} className="!mb-0 flex items-center gap-2 !text-slate-800">
                        <Moon size={18} />
                        当前 HDR 暗场样本
                    </Title>
                    {currentFrame && (
                        <Space wrap size={[6, 6]}>
                            <Tag color="purple">HDR_DARK</Tag>
                            <Tag color="cyan">诊断主图：max(HG_DARK, LG_DARK)</Tag>
                            <Tag color="default">不参与普通光谱提取</Tag>
                        </Space>
                    )}
                </div>

                {currentFrame ? (
                    <div className="space-y-4">
                        <Card size="small" title="HG/LG暗场输入平面" className="border border-slate-200 bg-white">
                            {activePlaneItems.length > 0 ? (
                                <div className="grid gap-4 md:grid-cols-2">
                                    {activePlaneItems.map((item) => (
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
                            ) : (
                                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前样本没有返回HG/LG预览图" />
                            )}
                        </Card>

                        <div className="grid gap-4 xl:grid-cols-[minmax(50%,1.1fr)_minmax(320px,0.9fr)] xl:items-start">
                            <ImageVersionPreview
                                frame={currentFrame}
                                emptyText="暂无HDR暗场诊断主图"
                                imageAreaClassName="min-h-[360px] xl:min-h-[420px]"
                                rawLabel="暗场诊断主图"
                                rawDescription="逐像素取HG/LG暗场中较大的DN生成，仅用于预览和审计；真实HDR暗场校准应使用单独保存的HG/LG平面。"
                            />

                            <div className="space-y-3">
                                <div className="grid gap-2 rounded-lg border border-slate-200 bg-slate-50 p-3 text-sm text-slate-600 sm:grid-cols-2 xl:grid-cols-1 2xl:grid-cols-2">
                                    <div>
                                        <Text className="block text-xs text-slate-500">采集类型</Text>
                                        <Tag color="purple" className="mt-1">HDR暗场样本</Tag>
                                    </div>
                                    <div>
                                        <Text className="block text-xs text-slate-500">尺寸</Text>
                                        <span className="font-medium text-slate-800">
                                            {currentFrame.width} x {currentFrame.height}
                                        </span>
                                    </div>
                                    <div>
                                        <Text className="block text-xs text-slate-500">接收时间</Text>
                                        <span className="font-medium text-slate-800">
                                            {new Date(currentFrame.timestamp).toLocaleString("zh-CN")}
                                        </span>
                                    </div>
                                    <div>
                                        <Text className="block text-xs text-slate-500">payload长度</Text>
                                        <span className="font-medium text-slate-800">{currentFrame.payloadLength} bytes</span>
                                    </div>
                                    <div className="sm:col-span-2 xl:col-span-1 2xl:col-span-2">
                                        <Text className="mb-1 block text-xs text-slate-500">原始数据查看</Text>
                                        <Space wrap size={6}>
                                            <ImagePixelDataViewer
                                                frame={currentFrame}
                                                triggerLabel="查看HDR暗场诊断RAW16像素"
                                            />
                                            <FpgaPayloadDataViewer
                                                frame={currentFrame}
                                                triggerLabel="查看原始HDR暗场payload映射"
                                            />
                                        </Space>
                                    </div>
                                </div>

                                <div className="grid gap-2 sm:grid-cols-2">
                                    <Card size="small" className="bg-slate-50">
                                        <Text className="block text-xs text-slate-500">HG暗场 mean / std</Text>
                                        <Text className="font-semibold text-slate-800">
                                            {formatNumber(hgMean)} / {formatNumber(hgStddev)} DN
                                        </Text>
                                    </Card>
                                    <Card size="small" className="bg-slate-50">
                                        <Text className="block text-xs text-slate-500">LG暗场 mean / std</Text>
                                        <Text className="font-semibold text-slate-800">
                                            {formatNumber(lgMean)} / {formatNumber(lgStddev)} DN
                                        </Text>
                                    </Card>
                                    <Card size="small" className="bg-slate-50">
                                        <Text className="block text-xs text-slate-500">HG/LG较亮像素占比</Text>
                                        <Text className="font-semibold text-slate-800">
                                            {formatRatio(hgDominantRatio)} / {formatRatio(lgDominantRatio)}
                                        </Text>
                                    </Card>
                                    <Card size="small" className="bg-slate-50">
                                        <Text className="block text-xs text-slate-500">≥512DN亮暗场比例</Text>
                                        <Text className="font-semibold text-slate-800">
                                            {formatRatio(hgBrightRatio, 3)} / {formatRatio(lgBrightRatio, 3)}
                                        </Text>
                                    </Card>
                                </div>
                            </div>
                        </div>
                    </div>
                ) : (
                    <Empty
                        image={Empty.PRESENTED_IMAGE_SIMPLE}
                        description={<span className="text-slate-400">暂无HDR暗场样本，请先采集一帧</span>}
                        className="py-12"
                    />
                )}
            </Card>

            <Card className="border border-slate-200 bg-white shadow-sm">
                <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
                    <div>
                        <Title level={5} className="!mb-0 flex items-center gap-2 !text-slate-800">
                            <Database size={18} />
                            HDR暗场历史样本
                        </Title>
                        <Text className="text-xs text-slate-500">
                            历史样本单独管理，不混入普通光谱图像和HDR融合光谱图像历史
                        </Text>
                    </div>
                    <Space wrap>
                        <Tag color="blue">共 {history.length} 帧</Tag>
                        <Button icon={<RefreshCw size={16} />} onClick={() => void loadHistory()} loading={loadingHistory}>
                            刷新
                        </Button>
                    </Space>
                </div>

                {loadingHistory && history.length === 0 ? (
                    <div className="flex justify-center py-12">
                        <Spin tip="正在加载HDR暗场历史" />
                    </div>
                ) : history.length === 0 ? (
                    <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无HDR暗场历史样本" className="py-10" />
                ) : (
                    <Table
                        columns={columns}
                        dataSource={history}
                        rowKey="id"
                        loading={loadingHistory}
                        pagination={{
                            pageSize: 8,
                            showSizeChanger: true,
                            showTotal: (total) => `共 ${total} 帧`,
                        }}
                    />
                )}
            </Card>

            <Modal
                title={previewFrame ? `HDR暗场样本预览 #${previewFrame.id}` : "HDR暗场样本预览"}
                open={Boolean(previewFrame)}
                onCancel={() => setPreviewFrame(null)}
                footer={null}
                width="92vw"
                style={{ maxWidth: 1280, top: 24 }}
                destroyOnClose
            >
                {previewFrame && (
                    <div className="space-y-4">
                        <ImageVersionPreview
                            frame={previewFrame}
                            emptyText="暂无HDR暗场诊断主图"
                            imageAreaClassName="min-h-[360px]"
                            rawLabel="暗场诊断主图"
                            rawDescription="逐像素取HG/LG暗场中较大的DN生成，仅用于预览和审计。"
                        />
                        <Card size="small" title="HG/LG暗场平面">
                            {previewPlaneItems.length > 0 ? (
                                <div className="grid gap-4 md:grid-cols-2">
                                    {previewPlaneItems.map((item) => (
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
                                                <Text className="font-medium text-slate-800">{item.label}</Text>
                                            </div>
                                        </button>
                                    ))}
                                </div>
                            ) : (
                                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有HG/LG平面预览" />
                            )}
                        </Card>
                    </div>
                )}
            </Modal>

            <Modal
                title={zoomItem?.label ?? "HDR暗场平面预览"}
                open={Boolean(zoomItem)}
                footer={null}
                onCancel={() => setZoomItem(null)}
                width="88vw"
                style={{ maxWidth: 1200, top: 28 }}
                destroyOnClose
            >
                {zoomItem && (
                    <div className="space-y-3">
                        <div className="flex max-h-[76vh] items-center justify-center overflow-auto rounded-lg bg-slate-950 p-4">
                            <img src={zoomItem.url} alt={zoomItem.label} className="max-h-full max-w-full object-contain" />
                        </div>
                        <Text className="block text-xs text-slate-500">{zoomItem.description}</Text>
                    </div>
                )}
            </Modal>
        </div>
    );
};

export default HdrDarkCapturePage;
