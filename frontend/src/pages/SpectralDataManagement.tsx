import React, { useEffect, useState } from "react";
import { Button, Card, Empty, Modal, Space, Spin, Table, Tag, Typography } from "antd";
import type { ColumnsType } from "antd/es/table";
import { Clock, Database, Eye, Image as ImageIcon, RefreshCw, Trash2 } from "lucide-react";
import { toast } from "sonner";

import { jniBridgeService } from "../service/jniBridgeService";
import { useJNIStore } from "../store/jniStore";
import type { ImageFrameRecord } from "../types/jni";

const { Title, Text } = Typography;

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
                width={900}
            >
                {selectedFrame && (
                    <div className="space-y-4">
                        <div className="flex min-h-[420px] items-center justify-center rounded-md bg-slate-950">
                            {selectedFrame.imageDataUrl ? (
                                <img
                                    src={selectedFrame.imageDataUrl}
                                    alt="图像帧预览"
                                    className="max-h-[680px] max-w-full object-contain"
                                />
                            ) : (
                                <ImageIcon size={42} className="text-slate-500" />
                            )}
                        </div>

                        <div className="grid gap-4 rounded-md bg-gray-50 p-4 sm:grid-cols-2 lg:grid-cols-4">
                            <div>
                                <Text className="text-sm text-slate-500">尺寸</Text>
                                <div className="font-semibold text-slate-800">
                                    {selectedFrame.width} x {selectedFrame.height}
                                </div>
                            </div>
                            <div>
                                <Text className="text-sm text-slate-500">像素格式</Text>
                                <div className="font-semibold text-slate-800">{selectedFrame.pixelFormat}</div>
                            </div>
                            <div>
                                <Text className="text-sm text-slate-500">Payload</Text>
                                <div className="font-semibold text-slate-800">{selectedFrame.payloadLength} bytes</div>
                            </div>
                            <div>
                                <Text className="text-sm text-slate-500">完整性</Text>
                                <div className="font-semibold text-slate-800">
                                    {selectedFrame.integrityResultCode || "UNKNOWN"}
                                </div>
                            </div>
                        </div>
                    </div>
                )}
            </Modal>
        </div>
    );
};

export default SpectralDataManagementPage;
