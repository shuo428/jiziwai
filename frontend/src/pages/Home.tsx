import { useEffect } from "react";
import { Button, Card, Tag, Typography } from "antd";
import { Link } from "react-router-dom";
import { Activity, ArrowRight, Camera, Cpu, Database, MessageSquare, Plug } from "lucide-react";

import { useJNIStore } from "../store/jniStore";
import { jniBridgeService } from "../service/jniBridgeService";

const { Title, Text } = Typography;

const HomePage = () => {
    const { bridgeState, imageHistory, latestStatus, latestConfigAck } = useJNIStore();
    const recentFrames = imageHistory.slice(0, 5);

    // 首页可能是登录后的首个页面，因此直接从数据库加载最近图片。
    useEffect(() => {
        jniBridgeService.loadImageHistory().catch(() => {
            // 网络和鉴权错误由统一Axios拦截器提示，这里避免重复弹窗。
        });
    }, []);

    const stats = [
        {
            label: "连接状态",
            value: bridgeState.connected ? "已连接" : "未连接",
            icon: <Plug size={16} />,
            color: bridgeState.connected ? "green" : "default",
        },
        {
            label: "历史图像",
            value: imageHistory.length,
            icon: <Database size={16} />,
            color: "blue",
        },
        {
            label: "状态位",
            value: latestStatus ? "已获取" : "-",
            icon: <Activity size={16} />,
            color: latestStatus ? "cyan" : "default",
        },
        {
            label: "配置应答",
            value: latestConfigAck ? latestConfigAck.resultCode : "-",
            icon: <Cpu size={16} />,
            color: latestConfigAck?.resultCode === 0 ? "green" : "orange",
        },
    ];

    const quickActions = [
        {
            icon: <Camera size={20} className="text-blue-600" />,
            title: "光谱桥接控制",
            description: "连接设备、获取图像帧、查询状态并下发配置",
            link: "/spectral-data",
        },
        {
            icon: <Database size={20} className="text-green-600" />,
            title: "图像历史管理",
            description: "查看PostgreSQL和服务器中保存的历史图像帧",
            link: "/spectral-management",
        },
        {
            icon: <MessageSquare size={20} className="text-indigo-600" />,
            title: "AI 助手",
            description: "用自然语言触发连接和设备命令",
            link: "/chat",
        },
    ];

    return (
        <div className="space-y-6 p-6">
            <Card className="bg-white border border-gray-200 shadow-sm">
                <div className="flex items-center justify-between gap-4">
                    <div>
                        <Text className="text-slate-500 text-sm block mb-1">Spectra Bridge</Text>
                        <Title level={3} className="!mb-2 !text-slate-800">
                            光谱数据处理系统
                        </Title>
                        <Text className="text-slate-600 text-sm">
                            通过新的 SpectraBridgeNative JNI 链路连接 FPGA 并接收回调数据
                        </Text>
                    </div>
                    <div className="hidden h-16 w-16 items-center justify-center rounded-lg bg-blue-50 text-blue-600 sm:flex">
                        <Camera size={32} />
                    </div>
                </div>
            </Card>

            <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
                {stats.map((stat) => (
                    <Card key={stat.label} className="bg-white border border-gray-200 shadow-sm">
                        <div className="mb-2 flex items-start justify-between gap-3">
                            <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-slate-50 text-slate-600">
                                {stat.icon}
                            </div>
                            <Tag color={stat.color} className="text-xs font-semibold">
                                {stat.value}
                            </Tag>
                        </div>
                        <Text className="block text-xs text-slate-600">{stat.label}</Text>
                    </Card>
                ))}
            </div>

            <div>
                <Title level={5} className="!mb-3 !text-slate-800">
                    快捷操作
                </Title>
                <div className="grid gap-4 md:grid-cols-3">
                    {quickActions.map((action) => (
                        <Link to={action.link} key={action.title}>
                            <Card hoverable className="h-full bg-white border border-gray-200 shadow-sm">
                                <div className="flex items-center gap-4">
                                    <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-lg bg-slate-50">
                                        {action.icon}
                                    </div>
                                    <div className="min-w-0 flex-1">
                                        <Title level={5} className="!mb-1 !text-slate-800">
                                            {action.title}
                                        </Title>
                                        <Text className="text-sm text-slate-600">{action.description}</Text>
                                    </div>
                                    <ArrowRight size={18} className="shrink-0 text-slate-400" />
                                </div>
                            </Card>
                        </Link>
                    ))}
                </div>
            </div>

            <Card className="bg-white border border-gray-200 shadow-sm">
                <div className="mb-4 flex items-center justify-between">
                    <Title level={5} className="!mb-0 flex items-center gap-2 !text-slate-800">
                        <Database size={18} />
                        最近图像帧
                    </Title>
                    <Link to="/spectral-management">
                        <Button type="link" className="flex items-center gap-1">
                            查看全部 <ArrowRight size={14} />
                        </Button>
                    </Link>
                </div>

                {recentFrames.length === 0 ? (
                    <div className="py-8 text-center">
                        <Camera size={48} className="mx-auto mb-3 text-slate-300" />
                        <Text className="mb-2 block text-slate-500">暂无图像帧</Text>
                        <Text className="text-sm text-slate-400">前往“光谱桥接控制”页面连接设备并获取一帧</Text>
                    </div>
                ) : (
                    <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-5">
                        {recentFrames.map((frame) => (
                            <Link to="/spectral-management" key={frame.id}>
                                <div className="rounded-md border border-slate-200 bg-slate-50 p-2 transition hover:border-blue-300">
                                    <div className="flex aspect-video items-center justify-center overflow-hidden rounded bg-slate-950">
                                        {frame.imageDataUrl ? (
                                            <img src={frame.imageDataUrl} alt="历史图像帧" className="h-full w-full object-contain" />
                                        ) : (
                                            <Camera size={28} className="text-slate-500" />
                                        )}
                                    </div>
                                    <div className="mt-2 text-xs text-slate-600">
                                        {frame.width} x {frame.height}
                                    </div>
                                    <div className="text-xs text-slate-400">
                                        {new Date(frame.timestamp).toLocaleString("zh-CN")}
                                    </div>
                                </div>
                            </Link>
                        ))}
                    </div>
                )}
            </Card>
        </div>
    );
};

export default HomePage;
