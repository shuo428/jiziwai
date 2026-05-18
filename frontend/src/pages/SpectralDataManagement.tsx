import React, { useState } from "react";
import { Button, Card, Empty, Modal, Space, Table, Tag, Typography } from "antd";
import type { ColumnsType } from "antd/es/table";
import { Clock, Database, Eye, Image as ImageIcon, Save, Trash2 } from "lucide-react";
import { toast } from "sonner";

import { useJNIStore } from "../store/jniStore";
import type { ImageFrameRecord } from "../types/jni";

const { Title, Text } = Typography;

const SpectralDataManagementPage: React.FC = () => {
    const { imageHistory, actions } = useJNIStore();
    const [selectedFrame, setSelectedFrame] = useState<ImageFrameRecord | null>(null);
    const [previewVisible, setPreviewVisible] = useState(false);

    const handleSaveToDatabase = (record: ImageFrameRecord) => {
        toast.success("已保留图像帧", {
            description: `图像帧 ${record.width}x${record.height} 后续可接入数据库或 OSS`,
        });
    };

    const handleDiscard = (record: ImageFrameRecord) => {
        Modal.confirm({
            title: "确认丢弃",
            content: "确定要从 localStorage 删除这帧图像吗？",
            okText: "确认丢弃",
            cancelText: "取消",
            okButtonProps: { danger: true },
            onOk: () => {
                actions.removeImageFrame(record.id);
                toast.success("已丢弃");
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
            content: "确定要清空所有历史图像帧吗？此操作不可恢复。",
            okText: "确认清空",
            cancelText: "取消",
            okButtonProps: { danger: true },
            onOk: () => {
                actions.clearImageHistory();
                toast.success("已清空");
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
            title: "帧信息",
            key: "frame",
            render: (_, record) => (
                <div>
                    <div className="font-medium text-slate-800">
                        {record.width} x {record.height}
                    </div>
                    <div className="mt-1 text-xs text-slate-500">
                        8bit: {(record.raw8Length / 1024).toFixed(2)} KB · 16bit: {(record.raw16Length / 1024).toFixed(2)} KB
                    </div>
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
            width: 260,
            render: (_, record) => (
                <Space size="small">
                    <Button type="link" size="small" icon={<Eye size={14} />} onClick={() => handlePreview(record)}>
                        预览
                    </Button>
                    <Button type="primary" size="small" icon={<Save size={14} />} onClick={() => handleSaveToDatabase(record)}>
                        保留
                    </Button>
                    <Button danger size="small" icon={<Trash2 size={14} />} onClick={() => handleDiscard(record)}>
                        丢弃
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
                            <Text className="text-sm text-slate-500">查看和管理 localStorage 中缓存的单帧图像</Text>
                        </div>
                    </div>

                    <div className="flex items-center gap-3">
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

                {imageHistory.length === 0 ? (
                    <Empty
                        image={Empty.PRESENTED_IMAGE_SIMPLE}
                        description={
                            <div className="text-slate-500">
                                <div className="mb-2">暂无历史图像帧</div>
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
                        <span>图像帧预览</span>
                    </div>
                }
                open={previewVisible}
                onCancel={() => setPreviewVisible(false)}
                footer={[
                    <Button key="close" onClick={() => setPreviewVisible(false)}>
                        关闭
                    </Button>,
                    <Button
                        key="save"
                        type="primary"
                        icon={<Save size={14} />}
                        onClick={() => {
                            if (selectedFrame) {
                                handleSaveToDatabase(selectedFrame);
                                setPreviewVisible(false);
                            }
                        }}
                    >
                        保留
                    </Button>,
                ]}
                width={900}
            >
                {selectedFrame && (
                    <div className="space-y-4">
                        <div className="flex min-h-[420px] items-center justify-center rounded-md bg-slate-950">
                            {selectedFrame.imageDataUrl ? (
                                <img src={selectedFrame.imageDataUrl} alt="图像帧预览" className="max-h-[680px] max-w-full object-contain" />
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
                                <Text className="text-sm text-slate-500">8bit 数据</Text>
                                <div className="font-semibold text-slate-800">{selectedFrame.raw8Length} bytes</div>
                            </div>
                            <div>
                                <Text className="text-sm text-slate-500">16bit 数据</Text>
                                <div className="font-semibold text-slate-800">{selectedFrame.raw16Length} items</div>
                            </div>
                            <div>
                                <Text className="text-sm text-slate-500">接收时间</Text>
                                <div className="font-semibold text-slate-800">
                                    {new Date(selectedFrame.timestamp).toLocaleString("zh-CN")}
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
