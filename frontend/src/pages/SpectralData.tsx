import React, { useEffect, useMemo, useState } from "react";
import { Alert, Button, Card, Checkbox, Input, InputNumber, Tag, Typography } from "antd";
import {
    Activity,
    AlertCircle,
    Binary,
    Camera,
    CheckCircle,
    Plug,
    RotateCcw,
    Send,
    Settings,
    Unplug,
} from "lucide-react";
import { toast } from "sonner";

import { jniBridgeService } from "../service/jniBridgeService";
import { useJNIStore } from "../store/jniStore";

const { Title, Text } = Typography;

const CONFIG_BYTE_COUNT = 512;
const CONFIG_HEX_LENGTH = CONFIG_BYTE_COUNT * 2;
const byteIndexes = Array.from({ length: CONFIG_BYTE_COUNT }, (_, index) => index);

const normalizeHexText = (value: string): string =>
    value.replace(/0x/gi, "").replace(/[^0-9a-fA-F]/g, "").toUpperCase();

const parseLittleEndianHexBytes = (value: string): number[] => {
    const normalized = normalizeHexText(value);
    if (normalized.length !== CONFIG_HEX_LENGTH) {
        throw new Error(`需要 ${CONFIG_HEX_LENGTH} 个十六进制字符，当前为 ${normalized.length} 个`);
    }

    const parsedBytes: number[] = [];
    for (let cursor = normalized.length; cursor > 0; cursor -= 2) {
        parsedBytes.push(Number.parseInt(normalized.slice(cursor - 2, cursor), 16));
    }
    return parsedBytes;
};

const toLittleEndianHexText = (bytes: number[]): string =>
    bytes
        .slice(0, CONFIG_BYTE_COUNT)
        .map((value) => Math.max(0, Math.min(255, Math.trunc(value))).toString(16).padStart(2, "0").toUpperCase())
        .reverse()
        .join("");

const SpectralDataPage: React.FC = () => {
    const [loadingAction, setLoadingAction] = useState<string | null>(null);
    const [configHexText, setConfigHexText] = useState("");
    const {
        connectionForm,
        bridgeState,
        websocketConnected,
        error,
        currentImage,
        latestStatus,
        latestConfigAck,
        configBytes,
        imageHistory,
        actions,
    } = useJNIStore();

    const connected = bridgeState.connected;

    useEffect(() => {
        jniBridgeService.initialize().catch((err: Error) => {
            actions.setError(err.message);
        });
    }, [actions]);

    const connectionSummary = useMemo(() => {
        if (!connected) {
            return "未连接";
        }
        return `${bridgeState.host}:${bridgeState.controlPort} / image:${bridgeState.imagePort}`;
    }, [bridgeState.controlPort, bridgeState.host, bridgeState.imagePort, connected]);

    const runAction = async (actionKey: string, task: () => Promise<unknown>, successMessage: string) => {
        setLoadingAction(actionKey);
        actions.setError(null);
        try {
            await task();
            toast.success(successMessage);
        } catch (err: any) {
            const message = err?.message || "操作失败";
            actions.setError(message);
            toast.error(message);
        } finally {
            setLoadingAction(null);
        }
    };

    const handleConnect = () =>
        runAction(
            "connect",
            () => jniBridgeService.connect(),
            "连接成功",
        );

    const handleDisconnect = () =>
        runAction(
            "disconnect",
            () => jniBridgeService.disconnect(),
            "已断开连接",
        );

    const handleReset = () =>
        runAction(
            "reset",
            () => jniBridgeService.sendReset(),
            "复位命令已发送",
        );

    const handleTriggerOnce = () =>
        runAction(
            "trigger",
            () => jniBridgeService.triggerOnceAndWaitForFrame(),
            "已获取一帧图像",
        );

    const handleQueryStatus = () =>
        runAction(
            "status",
            () => jniBridgeService.queryStatusAndWait(),
            "状态查询完成",
        );

    const handleSendFullConfig = () =>
        runAction(
            "config",
            () => jniBridgeService.sendFullConfigAndWait(configBytes),
            "完整配置已下发",
        );

    const handleParseHexConfig = () => {
        try {
            actions.replaceConfigBytes(parseLittleEndianHexBytes(configHexText));
            setConfigHexText(normalizeHexText(configHexText));
            actions.setError(null);
            toast.success("十六进制配置已解析到表格");
        } catch (err: any) {
            const message = err?.message || "十六进制配置解析失败";
            actions.setError(message);
            toast.error(message);
        }
    };

    const handleGenerateHexConfig = () => {
        setConfigHexText(toLittleEndianHexText(configBytes));
        toast.success("已从表格生成十六进制配置");
    };

    return (
        <div className="space-y-6 p-6">
            <Card className="bg-white border border-gray-200 shadow-sm">
                <div className="flex flex-wrap items-center justify-between gap-4">
                    <div className="flex items-center gap-3">
                        <div className="p-2.5 bg-blue-500 rounded-lg">
                            <Plug size={22} className="text-white" />
                        </div>
                        <div>
                            <div className="flex flex-wrap items-center gap-2">
                                <Title level={4} className="!mb-0 !text-slate-800">
                                    光谱桥接控制
                                </Title>
                                <Tag color={connected ? "green" : "default"} className="border-0">
                                    {connected ? "已连接" : "未连接"}
                                </Tag>
                                <Tag color={websocketConnected ? "blue" : "orange"} className="border-0">
                                    WS {websocketConnected ? "在线" : "离线"}
                                </Tag>
                            </div>
                            <Text className="text-slate-500 text-sm">{connectionSummary}</Text>
                        </div>
                    </div>

                    <div className="flex flex-wrap gap-2">
                        <Button
                            type="primary"
                            icon={<Plug size={16} />}
                            loading={loadingAction === "connect"}
                            disabled={loadingAction !== null || connected}
                            onClick={handleConnect}
                        >
                            连接
                        </Button>
                        <Button
                            danger
                            icon={<Unplug size={16} />}
                            loading={loadingAction === "disconnect"}
                            disabled={loadingAction !== null || !connected}
                            onClick={handleDisconnect}
                        >
                            断开
                        </Button>
                    </div>
                </div>

                {error && (
                    <Alert
                        message="操作失败"
                        description={error}
                        type="error"
                        icon={<AlertCircle size={16} />}
                        showIcon
                        closable
                        className="mt-5 rounded-md"
                        onClose={() => actions.setError(null)}
                    />
                )}

                <div className="mt-6 grid gap-4 lg:grid-cols-[1.2fr_1fr_1fr_auto]">
                    <div>
                        <Text className="mb-2 block text-sm text-slate-600">Host</Text>
                        <Input
                            value={connectionForm.host}
                            onChange={(event) => actions.setConnectionField("host", event.target.value)}
                            placeholder="例如 192.168.1.10"
                            disabled={connected}
                        />
                    </div>
                    <div>
                        <Text className="mb-2 block text-sm text-slate-600">Control Port</Text>
                        <InputNumber
                            min={1}
                            max={65535}
                            value={connectionForm.controlPort || null}
                            onChange={(value) => actions.setConnectionField("controlPort", Number(value || 0))}
                            className="w-full"
                            disabled={connected}
                        />
                    </div>
                    <div>
                        <Text className="mb-2 block text-sm text-slate-600">Image Port</Text>
                        <InputNumber
                            min={1}
                            max={65535}
                            value={connectionForm.imagePort || null}
                            onChange={(value) => actions.setConnectionField("imagePort", Number(value || 0))}
                            className="w-full"
                            disabled={connected}
                        />
                    </div>
                    <div className="flex items-end pb-1">
                        <Checkbox
                            checked={connectionForm.verifyCrc}
                            disabled={connected}
                            onChange={(event) => actions.setConnectionField("verifyCrc", event.target.checked)}
                        >
                            CRC
                        </Checkbox>
                    </div>
                </div>
            </Card>

            <div className="grid gap-6 xl:grid-cols-[1.1fr_0.9fr]">
                <Card className="bg-white border border-gray-200 shadow-sm">
                    <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
                        <Title level={5} className="!mb-0 flex items-center gap-2 !text-slate-800">
                            <Camera size={18} />
                            当前图像帧
                        </Title>
                        <Button
                            type="primary"
                            icon={<Camera size={16} />}
                            loading={loadingAction === "trigger"}
                            disabled={!connected || loadingAction !== null}
                            onClick={handleTriggerOnce}
                        >
                            获取一帧
                        </Button>
                    </div>

                    <div className="flex min-h-[360px] items-center justify-center rounded-md border border-slate-200 bg-slate-950">
                        {currentImage?.imageDataUrl ? (
                            <img
                                src={currentImage.imageDataUrl}
                                alt="当前光谱图像"
                                className="max-h-[520px] max-w-full object-contain"
                            />
                        ) : (
                            <div className="text-center text-slate-400">
                                <Camera size={42} className="mx-auto mb-3 opacity-60" />
                                <Text className="text-slate-400">暂无图像帧</Text>
                            </div>
                        )}
                    </div>

                    {currentImage && (
                        <div className="mt-3 grid gap-2 text-sm text-slate-600 sm:grid-cols-3">
                            <div>尺寸：{currentImage.width} x {currentImage.height}</div>
                            <div>8bit：{currentImage.raw8Length} bytes</div>
                            <div>{new Date(currentImage.timestamp).toLocaleString("zh-CN")}</div>
                        </div>
                    )}
                </Card>

                <Card className="bg-white border border-gray-200 shadow-sm">
                    <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
                        <Title level={5} className="!mb-0 flex items-center gap-2 !text-slate-800">
                            <Activity size={18} />
                            命令与原始状态
                        </Title>
                        <div className="flex flex-wrap gap-2">
                            <Button
                                icon={<RotateCcw size={16} />}
                                loading={loadingAction === "reset"}
                                disabled={!connected || loadingAction !== null}
                                onClick={handleReset}
                            >
                                复位
                            </Button>
                            <Button
                                icon={<Binary size={16} />}
                                loading={loadingAction === "status"}
                                disabled={!connected || loadingAction !== null}
                                onClick={handleQueryStatus}
                            >
                                查询状态
                            </Button>
                        </div>
                    </div>

                    <div className="space-y-4">
                        <div className="rounded-md border border-slate-200 bg-slate-50 p-4">
                            <Text className="mb-2 block text-sm text-slate-500">状态位</Text>
                            <div className="break-all rounded bg-white p-3 font-mono text-sm text-slate-800">
                                {latestStatus?.statusBinary || "暂无状态数据"}
                            </div>
                            {latestStatus && (
                                <div className="mt-2 text-xs text-slate-500">
                                    statusBits={latestStatus.statusBits} · errorCode={latestStatus.errorCode}
                                </div>
                            )}
                        </div>

                        <div className="rounded-md border border-slate-200 bg-slate-50 p-4">
                            <Text className="mb-2 block text-sm text-slate-500">配置应答</Text>
                            {latestConfigAck ? (
                                <div className="font-mono text-sm text-slate-800">
                                    resultCode={latestConfigAck.resultCode} · failedAddr={latestConfigAck.failedAddr}
                                </div>
                            ) : (
                                <Text className="text-slate-500">暂无配置应答</Text>
                            )}
                        </div>

                        <div className="rounded-md border border-slate-200 bg-slate-50 p-4">
                            <Text className="mb-2 block text-sm text-slate-500">数据库历史帧</Text>
                            <div className="flex items-center gap-2">
                                <Tag color="green">PostgreSQL</Tag>
                                <Text className="text-sm text-slate-700">{imageHistory.length} 帧</Text>
                            </div>
                        </div>
                    </div>
                </Card>
            </div>

            <Card className="bg-white border border-gray-200 shadow-sm">
                <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
                    <Title level={5} className="!mb-0 flex items-center gap-2 !text-slate-800">
                        <Settings size={18} />
                        FPGA 完整配置
                    </Title>
                    <div className="flex flex-wrap gap-2">
                        <Button onClick={() => actions.resetConfigBytes()} disabled={loadingAction !== null}>
                            清零
                        </Button>
                        <Button
                            type="primary"
                            icon={<Send size={16} />}
                            loading={loadingAction === "config"}
                            disabled={!connected || loadingAction !== null}
                            onClick={handleSendFullConfig}
                        >
                            发送 512 字节
                        </Button>
                    </div>
                </div>

                <div className="mb-4 rounded-md border border-slate-200 bg-slate-50 p-4">
                    <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
                        <div>
                            <Text className="block text-sm font-medium text-slate-700">十六进制配置输入</Text>
                            <Text className="text-xs text-slate-500">
                                共 512 字节，输入 1024 个十六进制字符；最右侧低位字节解析为 #0。
                            </Text>
                        </div>
                        <div className="flex flex-wrap gap-2">
                            <Button onClick={handleParseHexConfig} disabled={loadingAction !== null}>
                                解析到表格
                            </Button>
                            <Button onClick={handleGenerateHexConfig} disabled={loadingAction !== null}>
                                从表格生成
                            </Button>
                            <Button onClick={() => setConfigHexText("")} disabled={loadingAction !== null}>
                                清空输入
                            </Button>
                        </div>
                    </div>
                    <Input.TextArea
                        value={configHexText}
                        onChange={(event) => setConfigHexText(event.target.value)}
                        autoSize={{ minRows: 3, maxRows: 8 }}
                        spellCheck={false}
                        className="font-mono"
                        placeholder="例如：0A12...，其中 12 会解析为 #0，0A 会解析为 #1"
                    />
                    <div className="mt-2 text-xs text-slate-500">
                        已识别 {normalizeHexText(configHexText).length} / {CONFIG_HEX_LENGTH} 个十六进制字符
                    </div>
                </div>

                <div className="max-h-[420px] overflow-auto rounded-md border border-slate-200 p-3">
                    <div className="grid grid-cols-2 gap-2 sm:grid-cols-4 md:grid-cols-8 xl:grid-cols-16">
                        {byteIndexes.map((index) => (
                            <label key={index} className="block">
                                <span className="mb-1 block text-[11px] text-slate-500">#{index}</span>
                                <InputNumber
                                    min={0}
                                    max={255}
                                    size="small"
                                    value={configBytes[index]}
                                    onChange={(value) => actions.setConfigByte(index, Number(value || 0))}
                                    className="w-full"
                                    disabled={loadingAction === "config"}
                                />
                            </label>
                        ))}
                    </div>
                </div>

                {latestConfigAck?.resultCode === 0 && (
                    <Alert
                        className="mt-4 rounded-md"
                        type="success"
                        showIcon
                        icon={<CheckCircle size={16} />}
                        message="配置已确认"
                        description={`failedAddr=${latestConfigAck.failedAddr}`}
                    />
                )}
            </Card>
        </div>
    );
};

export default SpectralDataPage;
