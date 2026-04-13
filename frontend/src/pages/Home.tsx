import { Card, Typography, Progress, Tag, Button } from 'antd';
import { Link } from 'react-router-dom';
import { Radio, MessageSquare, Activity, Database, Cpu, ArrowRight } from 'lucide-react';
import { useJNIStore } from '../store/jniStore';

const { Title, Text } = Typography;

const HomePage = () => {
    const { isRunning, isCapturing, spectralDataList, receivedCount, totalCount } = useJNIStore();

    const quickActions = [
        {
            icon: <Radio size={20} className="text-blue-600" />,
            title: '获取光谱数据',
            description: '启动持续监听或指定次数采集',
            link: '/spectral-data',
            status: 'active',
        },
        {
            icon: <Database size={20} className="text-green-600" />,
            title: '光谱数据管理',
            description: '查看和管理已采集的光谱数据',
            link: '/spectral-management',
            status: 'active',
        },
        {
            icon: <MessageSquare size={20} className="text-slate-400" />,
            title: 'AI 助手',
            description: '与 AI 对话进行数据分析',
            link: '/chat',
            status: 'coming',
        },
    ];

    const stats = [
        { 
            label: '接口状态', 
            value: isRunning ? '监听中' : isCapturing ? '采集中' : '空闲', 
            icon: <Radio size={16} />, 
            color: isRunning || isCapturing ? 'green' : 'blue' 
        },
        { 
            label: '光谱数据', 
            value: spectralDataList.length, 
            icon: <Database size={16} />, 
            color: 'purple' 
        },
        { 
            label: '采集进度', 
            value: isCapturing ? `${receivedCount}/${totalCount}` : '-', 
            icon: <Activity size={16} />, 
            color: 'orange' 
        },
        { 
            label: '数据大小', 
            value: `${(spectralDataList.reduce((sum, item) => sum + item.size, 0) / 1024 / 1024).toFixed(2)} MB`, 
            icon: <Cpu size={16} />, 
            color: 'cyan' 
        },
    ];

    const recentData = spectralDataList.slice(-5).reverse();

    return (
        <div className="space-y-6 p-6">
            {/* Welcome Card */}
            <Card className="bg-gradient-to-r from-blue-500 to-blue-600 border-0 shadow-md">
                <div className="flex items-center justify-between">
                    <div>
                        <Text className="text-blue-100 text-sm block mb-1">欢迎使用</Text>
                        <Title level={3} className="!text-white !mb-2">光谱数据处理系统</Title>
                        <Text className="text-blue-50 text-sm">通过 JNI 桥接获取和管理 FPGA 光谱数据</Text>
                    </div>
                    <div className="w-16 h-16 rounded-full bg-white/20 backdrop-blur-sm flex items-center justify-center">
                        <Radio size={32} className="text-white" />
                    </div>
                </div>
            </Card>

            {/* System Stats */}
            <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
                {stats.map((stat, index) => (
                    <Card key={index} className="bg-white border border-gray-200 shadow-sm hover:shadow-md transition-shadow">
                        <div className="flex items-start justify-between mb-2">
                            <div className={`w-8 h-8 rounded-lg bg-${stat.color}-50 flex items-center justify-center`}>
                                <span className={`text-${stat.color}-600`}>{stat.icon}</span>
                            </div>
                            <Tag color={stat.color} className="text-xs font-semibold">{stat.value}</Tag>
                        </div>
                        <Text className="text-slate-600 text-xs block">{stat.label}</Text>
                    </Card>
                ))}
            </div>

            {/* Quick Actions */}
            <div>
                <Title level={5} className="!text-slate-800 !mb-3">快捷操作</Title>
                <div className="grid md:grid-cols-3 gap-4">
                    {quickActions.map((action, index) => (
                        <Link to={action.link} key={index} className={action.status === 'coming' ? 'pointer-events-none' : ''}>
                            <Card
                                hoverable={action.status === 'active'}
                                className={`h-full bg-white border border-gray-200 shadow-sm ${action.status === 'active' ? 'hover:border-blue-400 hover:shadow-md' : 'opacity-60'} transition-all`}
                            >
                                <div className="flex items-center gap-4">
                                    <div className={`w-12 h-12 rounded-lg ${action.status === 'active' ? 'bg-blue-50' : 'bg-slate-50'} flex items-center justify-center flex-shrink-0`}>
                                        {action.icon}
                                    </div>
                                    <div className="flex-1">
                                        <div className="flex items-center gap-2 mb-1">
                                            <Title level={5} className="!mb-0 !text-slate-800">
                                                {action.title}
                                            </Title>
                                            {action.status === 'coming' && (
                                                <Tag color="default" className="text-xs">即将推出</Tag>
                                            )}
                                        </div>
                                        <Text className="text-slate-600 text-sm">{action.description}</Text>
                                    </div>
                                    {action.status === 'active' && (
                                        <ArrowRight size={18} className="text-slate-400 flex-shrink-0" />
                                    )}
                                </div>
                            </Card>
                        </Link>
                    ))}
                </div>
            </div>

            {/* Recent Spectral Data */}
            <Card className="bg-white border border-gray-200 shadow-sm">
                <div className="flex items-center justify-between mb-4">
                    <Title level={5} className="!mb-0 !text-slate-800 flex items-center gap-2">
                        <Database size={18} />
                        最近光谱数据
                    </Title>
                    <Link to="/spectral-management">
                        <Button type="link" className="flex items-center gap-1">
                            查看全部 <ArrowRight size={14} />
                        </Button>
                    </Link>
                </div>
                
                {spectralDataList.length === 0 ? (
                    <div className="text-center py-8">
                        <Database size={48} className="text-slate-300 mx-auto mb-3" />
                        <Text className="text-slate-500 block mb-2">暂无光谱数据</Text>
                        <Text className="text-slate-400 text-sm">前往"获取光谱数据"页面开始采集</Text>
                    </div>
                ) : (
                    <div className="space-y-2">
                        {recentData.map((data) => (
                            <div 
                                key={data.id} 
                                className="flex items-center justify-between p-3 bg-gray-50 rounded-lg hover:bg-gray-100 transition-colors"
                            >
                                <div className="flex items-center gap-3 flex-1">
                                    <Tag color="blue" className="font-mono">
                                        #{data.index}
                                    </Tag>
                                    <div className="flex-1">
                                        <Text className="text-slate-800 font-medium block">{data.data}</Text>
                                        <Text className="text-slate-500 text-xs">
                                            {new Date(data.timestamp).toLocaleString('zh-CN')} · {(data.size / 1024).toFixed(2)} KB
                                        </Text>
                                    </div>
                                </div>
                                <Link to="/spectral-management">
                                    <Button type="text" size="small" icon={<ArrowRight size={14} />}>
                                        查看
                                    </Button>
                                </Link>
                            </div>
                        ))}
                    </div>
                )}
            </Card>

            {/* System Status */}
            {(isRunning || isCapturing) && (
                <Card className="bg-white border border-gray-200 shadow-sm">
                    <div className="flex items-center justify-between mb-4">
                        <Title level={5} className="!mb-0 !text-slate-800 flex items-center gap-2">
                            <Activity size={18} />
                            采集状态
                        </Title>
                        <Tag color="green" icon={<Activity size={12} />}>
                            运行中
                        </Tag>
                    </div>
                    <div className="space-y-3">
                        {isCapturing && (
                            <div>
                                <div className="flex justify-between mb-1">
                                    <Text className="text-slate-600 text-sm">采集进度</Text>
                                    <Text className="text-slate-800 text-sm font-medium">
                                        {receivedCount} / {totalCount} ({Math.round((receivedCount / totalCount) * 100)}%)
                                    </Text>
                                </div>
                                <Progress 
                                    percent={Math.round((receivedCount / totalCount) * 100)} 
                                    strokeColor="#3b82f6" 
                                    showInfo={false} 
                                />
                            </div>
                        )}
                        {isRunning && (
                            <div className="bg-blue-50 border border-blue-200 rounded-lg p-3">
                                <Text className="text-blue-800 text-sm">
                                    <strong>持续监听模式：</strong>系统正在持续接收来自 FPGA 的光谱数据
                                </Text>
                            </div>
                        )}
                    </div>
                </Card>
            )}
        </div>
    );
};

export default HomePage;
