import React, { useState } from 'react';
import { Card, Button, Table, Typography, Tag, Space, Modal, Empty } from 'antd';
import { Database, Save, Trash2, Image as ImageIcon, Clock } from 'lucide-react';
import { useJNIStore } from '../store/jniStore';
import type { SpectralData } from '../store/jniStore';
import { toast } from 'sonner';
import type { ColumnsType } from 'antd/es/table';

const { Title, Text } = Typography;

const SpectralDataManagementPage: React.FC = () => {
    const { spectralDataList, actions } = useJNIStore();
    const [selectedData, setSelectedData] = useState<SpectralData | null>(null);
    const [previewVisible, setPreviewVisible] = useState(false);

    const handleSaveToDatabase = (record: SpectralData) => {
        toast.success('保存成功', {
            description: `光谱数据 #${record.index} 已保存到数据库`
        });
    };

    const handleDiscard = (record: SpectralData) => {
        Modal.confirm({
            title: '确认丢弃',
            content: `确定要丢弃光谱数据 #${record.index} 吗？此操作不可恢复。`,
            okText: '确认丢弃',
            cancelText: '取消',
            okButtonProps: { danger: true },
            onOk: () => {
                actions.removeSpectralData(record.id);
                toast.success('已丢弃', {
                    description: `光谱数据 #${record.index} 已被丢弃`
                });
            },
        });
    };

    const handlePreview = (record: SpectralData) => {
        setSelectedData(record);
        setPreviewVisible(true);
    };

    const handleClearAll = () => {
        Modal.confirm({
            title: '确认清空',
            content: `确定要清空所有光谱数据吗？此操作不可恢复。`,
            okText: '确认清空',
            cancelText: '取消',
            okButtonProps: { danger: true },
            onOk: () => {
                actions.clearSpectralData();
                toast.success('已清空', {
                    description: '所有光谱数据已被清空'
                });
            },
        });
    };

    const columns: ColumnsType<SpectralData> = [
        {
            title: '序号',
            dataIndex: 'index',
            key: 'index',
            width: 80,
            render: (index: number) => (
                <Tag color="blue" className="font-mono">
                    #{index}
                </Tag>
            ),
        },
        {
            title: '数据信息',
            dataIndex: 'data',
            key: 'data',
            render: (data: string, record: SpectralData) => (
                <div>
                    <div className="font-medium text-slate-800">{data}</div>
                    <div className="text-xs text-slate-500 mt-1">
                        大小: {(record.size / 1024).toFixed(2)} KB
                    </div>
                </div>
            ),
        },
        {
            title: '接收时间',
            dataIndex: 'timestamp',
            key: 'timestamp',
            width: 180,
            render: (timestamp: string) => (
                <div className="flex items-center gap-2 text-slate-600">
                    <Clock size={14} />
                    <span className="text-sm">
                        {new Date(timestamp).toLocaleString('zh-CN', {
                            year: 'numeric',
                            month: '2-digit',
                            day: '2-digit',
                            hour: '2-digit',
                            minute: '2-digit',
                            second: '2-digit',
                        })}
                    </span>
                </div>
            ),
        },
        {
            title: '操作',
            key: 'action',
            width: 280,
            render: (_, record: SpectralData) => (
                <Space size="small">
                    <Button
                        type="link"
                        size="small"
                        icon={<ImageIcon size={14} />}
                        onClick={() => handlePreview(record)}
                    >
                        预览
                    </Button>
                    <Button
                        type="primary"
                        size="small"
                        icon={<Save size={14} />}
                        onClick={() => handleSaveToDatabase(record)}
                    >
                        保存到数据库
                    </Button>
                    <Button
                        danger
                        size="small"
                        icon={<Trash2 size={14} />}
                        onClick={() => handleDiscard(record)}
                    >
                        丢弃
                    </Button>
                </Space>
            ),
        },
    ];

    return (
        <div className="space-y-6 p-6">
            <Card className="bg-white border border-gray-200 shadow-sm">
                <div className="flex items-center justify-between mb-6">
                    <div className="flex items-center gap-3">
                        <div className="p-2.5 bg-green-500 rounded-lg">
                            <Database size={22} className="text-white" />
                        </div>
                        <div>
                            <Title level={4} className="!mb-0 !text-slate-800">光谱数据管理</Title>
                            <Text className="text-slate-500 text-sm">
                                查看和管理接收到的光谱数据
                            </Text>
                        </div>
                    </div>
                    
                    <div className="flex items-center gap-3">
                        <Tag color="blue" className="text-sm px-3 py-1">
                            共 {spectralDataList.length} 条数据
                        </Tag>
                        {spectralDataList.length > 0 && (
                            <Button 
                                danger 
                                icon={<Trash2 size={16} />}
                                onClick={handleClearAll}
                            >
                                清空全部
                            </Button>
                        )}
                    </div>
                </div>

                {spectralDataList.length === 0 ? (
                    <Empty
                        image={Empty.PRESENTED_IMAGE_SIMPLE}
                        description={
                            <div className="text-slate-500">
                                <div className="mb-2">暂无光谱数据</div>
                                <div className="text-sm">请前往"获取光谱数据"页面开始采集</div>
                            </div>
                        }
                        className="py-12"
                    />
                ) : (
                    <Table
                        columns={columns}
                        dataSource={spectralDataList}
                        rowKey="id"
                        pagination={{
                            pageSize: 10,
                            showSizeChanger: true,
                            showTotal: (total) => `共 ${total} 条数据`,
                        }}
                        className="custom-table"
                    />
                )}
            </Card>

            <Modal
                title={
                    <div className="flex items-center gap-2">
                        <ImageIcon size={18} />
                        <span>光谱数据预览</span>
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
                            if (selectedData) {
                                handleSaveToDatabase(selectedData);
                                setPreviewVisible(false);
                            }
                        }}
                    >
                        保存到数据库
                    </Button>,
                ]}
                width={800}
            >
                {selectedData && (
                    <div className="space-y-4">
                        <div className="grid grid-cols-2 gap-4 p-4 bg-gray-50 rounded-lg">
                            <div>
                                <Text className="text-slate-500 text-sm">序号</Text>
                                <div className="text-slate-800 font-semibold">#{selectedData.index}</div>
                            </div>
                            <div>
                                <Text className="text-slate-500 text-sm">数据大小</Text>
                                <div className="text-slate-800 font-semibold">
                                    {(selectedData.size / 1024).toFixed(2)} KB
                                </div>
                            </div>
                            <div>
                                <Text className="text-slate-500 text-sm">接收时间</Text>
                                <div className="text-slate-800 font-semibold">
                                    {new Date(selectedData.timestamp).toLocaleString('zh-CN')}
                                </div>
                            </div>
                            <div>
                                <Text className="text-slate-500 text-sm">数据ID</Text>
                                <div className="text-slate-800 font-mono text-xs truncate">
                                    {selectedData.id}
                                </div>
                            </div>
                        </div>

                        <div className="border border-gray-200 rounded-lg p-4">
                            <Text className="text-slate-500 text-sm block mb-2">数据内容</Text>
                            <div className="bg-slate-50 p-4 rounded font-mono text-sm text-slate-700 max-h-96 overflow-auto">
                                {selectedData.data}
                            </div>
                        </div>

                        <div className="bg-blue-50 border border-blue-200 rounded-lg p-4">
                            <Text className="text-blue-800 text-sm">
                                <strong>提示：</strong>这里展示的是模拟数据。实际使用时，这里将显示光谱图像或像素矩阵数据的可视化。
                            </Text>
                        </div>
                    </div>
                )}
            </Modal>
        </div>
    );
};

export default SpectralDataManagementPage;
