import React, { useState } from 'react';
import { Card, Button, Alert, Typography, Tag, Tabs, InputNumber, Flex } from 'antd';
import { Radio, Play, AlertCircle, CheckCircle, Activity, Square, Hash } from 'lucide-react';
import { jniBridgeService } from '../service/jniBridgeService';
import { toast } from 'sonner';
import { useJNIStore } from '../store/jniStore';

const { Title, Paragraph, Text } = Typography;

const SpectralDataPage: React.FC = () => {
    const [loading, setLoading] = useState<boolean>(false);
    const [captureCount, setCaptureCount] = useState<number>(10);
    const { 
        isRunning, 
        isCapturing, 
        receivedCount, 
        totalCount, 
        spectralDataList,
        error, 
        actions 
    } = useJNIStore();

    const startContinuousListener = async () => {
        setLoading(true);
        actions.setError(null);
        actions.setResult(null);

        try {
            await jniBridgeService.startContinuousListener();
            toast.success('持续监听已启动', {
                description: '系统将持续接收光谱数据'
            });
        } catch (err: any) {
            actions.setError(err.response?.data?.message || err.message || 'Failed to connect to backend');
            toast.error('启动失败', {
                description: '无法启动持续监听'
            });
        } finally {
            setLoading(false);
        }
    };

    const stopContinuousListener = async () => {
        setLoading(true);
        actions.setError(null);

        try {
            await jniBridgeService.stopContinuousListener();
            toast.success('持续监听已停止', {
                description: '系统已停止接收光谱数据'
            });
        } catch (err: any) {
            actions.setError(err.response?.data?.message || err.message || 'Failed to stop listener');
            toast.error('停止失败', {
                description: '无法停止持续监听'
            });
        } finally {
            setLoading(false);
        }
    };

    const startCountBasedCapture = async () => {
        if (!captureCount || captureCount <= 0) {
            toast.error('参数错误', { description: '请输入有效的采集次数' });
            return;
        }

        setLoading(true);
        actions.setError(null);

        try {
            await jniBridgeService.captureCountBasedSpectralData(captureCount);
            toast.success('指定次数采集已启动', {
                description: `已按原有流程接收 ${captureCount} 条光谱数据`
            });
        } catch (err: any) {
            actions.setError(err.response?.data?.message || err.message || 'Failed to start capture');
            toast.error('启动失败', {
                description: '无法启动指定次数采集'
            });
        } finally {
            setLoading(false);
        }
    };


    const tabItems = [
        {
            key: 'continuous',
            label: (
                <span className="flex items-center gap-2">
                    <Radio size={16} />
                    持续监听接口
                </span>
            ),
            children: (
                <div className="space-y-4">
                    <div className="bg-gray-50 rounded-lg p-4 border border-gray-200">
                        <Text strong className="text-slate-800 block mb-2">持续监听</Text>
                        <Paragraph className="text-slate-600 mb-0 text-sm">
                            启动后将持续接收来自 FPGA 设备的光谱数据，直到手动停止。
                        </Paragraph>
                    </div>

                    {isRunning && (
                        <Alert
                            message="监听器正在运行"
                            description={
                                <div>
                                    <div>系统正在持续监听光谱数据...</div>
                                    <div className="mt-2 text-blue-600 font-semibold">
                                        已接收 {spectralDataList.length} 条光谱数据
                                    </div>
                                </div>
                            }
                            type="success"
                            icon={<CheckCircle size={16} />}
                            showIcon
                            className="rounded-md"
                        />
                    )}

                    <div className="flex gap-2">
                        <Button
                            type="primary"
                            icon={<Play size={16} />}
                            onClick={startContinuousListener}
                            loading={loading && !isRunning}
                            disabled={isRunning || loading || isCapturing}
                            size="large"
                        >
                            启动持续监听
                        </Button>
                        <Button
                            danger
                            icon={<Square size={16} />}
                            onClick={stopContinuousListener}
                            loading={loading && isRunning}
                            disabled={!isRunning || loading}
                            size="large"
                        >
                            停止监听
                        </Button>
                    </div>
                </div>
            ),
        },
        {
            key: 'count-based',
            label: (
                <span className="flex items-center gap-2">
                    <Hash size={16} />
                    指定次数获取接口
                </span>
            ),
            children: (
                <div className="space-y-4">
                    <div className="bg-gray-50 rounded-lg p-4 border border-gray-200">
                        <Text strong className="text-slate-800 block mb-2">工作模式</Text>
                        <Paragraph className="text-slate-600 mb-0 text-sm">
                            指定需要采集的光谱数据数量，系统将自动采集指定次数的数据。
                            每次采集到的数据会经过 CUDA 处理（增强 + 去噪）后返回。
                            采集完成后会显示总共接收的数据数量。
                        </Paragraph>
                    </div>

                    <div className="flex items-center gap-4">
                        <div>
                            <Text className="text-slate-600 text-sm block mb-2">采集次数</Text>
                            <InputNumber
                                min={1}
                                max={100}
                                value={captureCount}
                                onChange={(value) => setCaptureCount(value || 1)}
                                className="w-32"
                                size="large"
                                disabled={isCapturing || isRunning}
                            />
                        </div>
                    </div>

                    {isCapturing && (
                        <Alert
                            message="正在采集光谱数据"
                            description={
                                <div>
                                    <div className="mb-2">采集进度：{receivedCount} / {totalCount}</div>
                                    <div className="text-blue-600 font-semibold">
                                        {receivedCount < totalCount 
                                            ? `第 ${receivedCount} 个光谱数据接收完成...` 
                                            : `接收结束，共接收到 ${totalCount} 条光谱数据`}
                                    </div>
                                </div>
                            }
                            type="info"
                            icon={<Activity size={16} />}
                            showIcon
                            className="rounded-md"
                        />
                    )}

                    <div className="flex gap-2">
                        <Button
                            type="primary"
                            icon={<Play size={16} />}
                            onClick={startCountBasedCapture}
                            loading={loading}
                            disabled={isCapturing || isRunning || loading}
                            size="large"
                        >
                            开始采集
                        </Button>
                    </div>
                </div>
            ),
        },
    ];

    return (
        <div className="space-y-6 p-6">
            <Card className="bg-white border border-gray-200 shadow-sm">
                <div className="flex items-center justify-between mb-6">
                    <div className="flex items-center gap-3">
                        <div className="p-2.5 bg-blue-500 rounded-lg">
                            <Radio size={22} className="text-white" />
                        </div>
                        <div>
                            <div className="flex items-center gap-2">
                                <Title level={4} className="!mb-0 !text-slate-800">获取光谱数据</Title>
                                {(isRunning || isCapturing) && (
                                    <Tag color="processing" className="border-0 text-xs">
                                        <Flex gap="small" align='center'><Activity size={12}/>{isRunning ? '持续监听中' : '采集中'}</Flex>
                                    </Tag>
                                )}
                            </div>
                            <Text className="text-slate-500 text-sm">通过 JNI 桥接获取 FPGA 光谱数据</Text>
                        </div>
                    </div>
                </div>

                {error && (
                    <Alert
                        message="操作失败"
                        description={error}
                        type="error"
                        icon={<AlertCircle size={16} />}
                        showIcon
                        className="mb-6 rounded-md"
                        closable
                    />
                )}

                <Tabs 
                    items={tabItems} 
                    defaultActiveKey="continuous"
                    className="custom-tabs"
                />
            </Card>

            <Card className="bg-white border border-gray-200 shadow-sm">
                <Title level={5} className="!text-slate-800 !mb-3 flex items-center gap-2">
                    <Activity size={18} className="text-slate-600" />
                    接口说明
                </Title>
                <ul className="space-y-2 text-slate-600 text-sm">
                    <li className="flex items-start gap-2">
                        <div className="w-5 h-5 rounded-full bg-slate-100 flex items-center justify-center flex-shrink-0 mt-0.5 text-slate-600 font-semibold text-xs">1</div>
                        <span><strong>持续监听接口：</strong>持续接收 FPGA 发送的光谱数据</span>
                    </li>
                    <li className="flex items-start gap-2">
                        <div className="w-5 h-5 rounded-full bg-slate-100 flex items-center justify-center flex-shrink-0 mt-0.5 text-slate-600 font-semibold text-xs">2</div>
                        <span><strong>指定次数获取接口：</strong>指定采集次数，系统自动采集并处理数据</span>
                    </li>
                    <li className="flex items-start gap-2">
                        <div className="w-5 h-5 rounded-full bg-slate-100 flex items-center justify-center flex-shrink-0 mt-0.5 text-slate-600 font-semibold text-xs">3</div>
                        <span>接收到的光谱数据会自动显示接收进度和完成状态</span>
                    </li>
                    <li className="flex items-start gap-2">
                        <div className="w-5 h-5 rounded-full bg-slate-100 flex items-center justify-center flex-shrink-0 mt-0.5 text-slate-600 font-semibold text-xs">4</div>
                        <span>所有接收到的数据可以在"光谱数据管理"页面查看和操作</span>
                    </li>
                </ul>
            </Card>
        </div>
    );
};

export default SpectralDataPage;
