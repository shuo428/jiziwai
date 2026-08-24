import React, { useEffect, useState } from "react";
import { Alert, Button, Card, Input, InputNumber, Select, Tag, Tooltip, Typography } from "antd";
import { Activity, AlertCircle, Binary, CheckCircle, Database, Info, RotateCcw, Send, Settings } from "lucide-react";
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

const ConfigManagementPage: React.FC = () => {
    const [loadingAction, setLoadingAction] = useState<string | null>(null);
    const [configHexText, setConfigHexText] = useState("");
    const {
        bridgeState,
        error,
        latestStatus,
        latestConfigAck,
        configBytes,
        imageHistory,
        connectionForm,
        savedConnectionOptions,
        actions,
    } = useJNIStore();
    const connected = bridgeState.connected;
    const currentHost = connectionForm.host.trim();
    const currentControlPort = connectionForm.controlPort;
    const currentImagePort = connectionForm.imagePort;

    useEffect(() => {
        jniBridgeService.initialize().catch((err: Error) => {
            actions.setError(err.message);
        });
    }, [actions]);

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

    const handleSaveConnectionOption = (field: "host" | "controlPort" | "imagePort") => {
        if (field === "host" && !currentHost) {
            toast.error("Host 不能为空");
            return;
        }
        if (field === "controlPort" && (!Number.isInteger(currentControlPort) || currentControlPort < 1 || currentControlPort > 65535)) {
            toast.error("Control Port 必须在 1~65535 之间");
            return;
        }
        if (field === "imagePort" && (!Number.isInteger(currentImagePort) || currentImagePort < 1 || currentImagePort > 65535)) {
            toast.error("Image Port 必须在 1~65535 之间");
            return;
        }
        actions.saveConnectionOption(field);
        toast.success("连接参数保存项已更新");
    };

    return (
        <div className="space-y-6 p-6">
            <Card className="bg-white border border-gray-200 shadow-sm">
                <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
                    <div>
                        <Title level={4} className="!mb-1 !text-slate-800">
                            配置管理
                        </Title>
                        <Text className="text-sm text-slate-500">
                            设备复位、状态查询、原始状态位查看和 FPGA 512 字节完整配置下发集中在此处。
                        </Text>
                    </div>
                    <Tag color={connected ? "green" : "default"}>
                        {connected ? "设备已连接" : "设备未连接"}
                    </Tag>
                </div>
                {error && (
                    <Alert
                        message="操作失败"
                        description={error}
                        type="error"
                        icon={<AlertCircle size={16} />}
                        showIcon
                        closable
                        className="rounded-md"
                        onClose={() => actions.setError(null)}
                    />
                )}
            </Card>

            <Card className="bg-white border border-gray-200 shadow-sm">
                <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
                    <div>
                        <Title level={5} className="!mb-1 flex items-center gap-2 !text-slate-800">
                            <Settings size={18} />
                            连接参数保存项
                        </Title>
                        <Text className="text-sm text-slate-500">
                            常用 Host、Control Port 和 Image Port 分开保存；设备总览连接时可以下拉选择，也可以手动输入。
                        </Text>
                    </div>
                    <Tag color="blue">
                        {savedConnectionOptions.hosts.length} Host · {savedConnectionOptions.controlPorts.length} 控制端口 ·{" "}
                        {savedConnectionOptions.imagePorts.length} 图像端口
                    </Tag>
                </div>

                <div className="grid gap-4 lg:grid-cols-3">
                    <div className="rounded-lg border border-slate-200 bg-slate-50 p-4">
                        <Text className="mb-2 block text-sm font-medium text-slate-700">Host 保存项</Text>
                        <div className="flex gap-2">
                            <Input
                                value={connectionForm.host}
                                onChange={(event) => actions.setConnectionField("host", event.target.value)}
                                placeholder="例如 192.168.1.10"
                                disabled={connected}
                            />
                            <Button
                                type="primary"
                                disabled={!currentHost}
                                onClick={() => handleSaveConnectionOption("host")}
                            >
                                保存
                            </Button>
                        </div>
                        <div className="mt-3 flex min-h-8 flex-wrap gap-2">
                            {savedConnectionOptions.hosts.length === 0 ? (
                                <Text className="text-xs text-slate-400">暂无已保存 Host</Text>
                            ) : (
                                savedConnectionOptions.hosts.map((host) => (
                                    <Tag
                                        key={host}
                                        closable
                                        color={host === connectionForm.host ? "blue" : "default"}
                                        className={connected ? "m-0" : "m-0 cursor-pointer"}
                                        onClick={() => {
                                            if (!connected) {
                                                actions.setConnectionField("host", host);
                                            }
                                        }}
                                        onClose={(event) => {
                                            event.preventDefault();
                                            actions.removeConnectionOption("host", host);
                                        }}
                                    >
                                        {host}
                                    </Tag>
                                ))
                            )}
                        </div>
                    </div>

                    <div className="rounded-lg border border-slate-200 bg-slate-50 p-4">
                        <Text className="mb-2 block text-sm font-medium text-slate-700">Control Port 保存项</Text>
                        <div className="flex gap-2">
                            <InputNumber
                                min={1}
                                max={65535}
                                value={connectionForm.controlPort || null}
                                onChange={(value) => actions.setConnectionField("controlPort", Number(value || 0))}
                                className="w-full"
                                disabled={connected}
                            />
                            <Button
                                type="primary"
                                disabled={!Number.isInteger(currentControlPort) || currentControlPort < 1 || currentControlPort > 65535}
                                onClick={() => handleSaveConnectionOption("controlPort")}
                            >
                                保存
                            </Button>
                        </div>
                        <div className="mt-3 flex min-h-8 flex-wrap gap-2">
                            {savedConnectionOptions.controlPorts.length === 0 ? (
                                <Text className="text-xs text-slate-400">暂无已保存控制端口</Text>
                            ) : (
                                savedConnectionOptions.controlPorts.map((port) => (
                                    <Tag
                                        key={port}
                                        closable
                                        color={port === connectionForm.controlPort ? "blue" : "default"}
                                        className={connected ? "m-0" : "m-0 cursor-pointer"}
                                        onClick={() => {
                                            if (!connected) {
                                                actions.setConnectionField("controlPort", port);
                                            }
                                        }}
                                        onClose={(event) => {
                                            event.preventDefault();
                                            actions.removeConnectionOption("controlPort", port);
                                        }}
                                    >
                                        {port}
                                    </Tag>
                                ))
                            )}
                        </div>
                    </div>

                    <div className="rounded-lg border border-slate-200 bg-slate-50 p-4">
                        <Text className="mb-2 block text-sm font-medium text-slate-700">Image Port 保存项</Text>
                        <div className="flex gap-2">
                            <InputNumber
                                min={1}
                                max={65535}
                                value={connectionForm.imagePort || null}
                                onChange={(value) => actions.setConnectionField("imagePort", Number(value || 0))}
                                className="w-full"
                                disabled={connected}
                            />
                            <Button
                                type="primary"
                                disabled={!Number.isInteger(currentImagePort) || currentImagePort < 1 || currentImagePort > 65535}
                                onClick={() => handleSaveConnectionOption("imagePort")}
                            >
                                保存
                            </Button>
                        </div>
                        <div className="mt-3 flex min-h-8 flex-wrap gap-2">
                            {savedConnectionOptions.imagePorts.length === 0 ? (
                                <Text className="text-xs text-slate-400">暂无已保存图像端口</Text>
                            ) : (
                                savedConnectionOptions.imagePorts.map((port) => (
                                    <Tag
                                        key={port}
                                        closable
                                        color={port === connectionForm.imagePort ? "blue" : "default"}
                                        className={connected ? "m-0" : "m-0 cursor-pointer"}
                                        onClick={() => {
                                            if (!connected) {
                                                actions.setConnectionField("imagePort", port);
                                            }
                                        }}
                                        onClose={(event) => {
                                            event.preventDefault();
                                            actions.removeConnectionOption("imagePort", port);
                                        }}
                                    >
                                        {port}
                                    </Tag>
                                ))
                            )}
                        </div>
                    </div>
                </div>

                {connected && (
                    <Alert
                        className="mt-4 rounded-md"
                        type="info"
                        showIcon
                        message="当前设备已连接"
                        description="已连接时 Host 和端口输入不可修改；如需应用其他保存项，请先断开连接，再在设备总览下拉选择。"
                    />
                )}
            </Card>

            <Card className="bg-white border border-gray-200 shadow-sm">
                <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
                    <div>
                        <Title level={5} className="!mb-1 flex items-center gap-2 !text-slate-800">
                            <Settings size={18} />
                            采集图像全局规格
                        </Title>
                        <Text className="text-sm text-slate-500">
                            这里控制 C++ 端接收时用于校验 FPGA header 的宽高、像素格式和读出顺序；修改后需要重新连接设备才会生效。
                        </Text>
                    </div>
                    <Tag color={connected ? "green" : "blue"}>
                        当前 {connectionForm.expectedWidth}×{connectionForm.expectedHeight}
                    </Tag>
                </div>
                <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
                    <div>
                        <Text className="mb-2 block text-sm text-slate-600">图像宽度 expectedWidth</Text>
                        <InputNumber
                            min={1}
                            max={20000}
                            value={connectionForm.expectedWidth || null}
                            onChange={(value) => actions.setConnectionField("expectedWidth", Number(value || 0))}
                            className="w-full"
                            disabled={connected}
                        />
                    </div>
                    <div>
                        <Text className="mb-2 block text-sm text-slate-600">图像高度 expectedHeight</Text>
                        <InputNumber
                            min={1}
                            max={20000}
                            value={connectionForm.expectedHeight || null}
                            onChange={(value) => actions.setConnectionField("expectedHeight", Number(value || 0))}
                            className="w-full"
                            disabled={connected}
                        />
                    </div>
                    <div>
                        <div className="mb-2 flex items-center gap-1 text-sm text-slate-600">
                            <span>像素格式 pixelFormat</span>
                            <Tooltip title="描述单个像素如何打包。当前 RAW16_LOW12 表示每个像素用 16 bit 容器传输，其中低 12 bit 是真实 CMOS DN 值，高 4 bit 应为 0。">
                                <Info size={14} className="cursor-help text-slate-400" />
                            </Tooltip>
                        </div>
                        <Select
                            value={connectionForm.pixelFormat}
                            onChange={(value) => actions.setConnectionField("pixelFormat", value)}
                            className="w-full"
                            disabled={connected}
                            options={[{ label: "RAW16_LOW12", value: "RAW16_LOW12" }]}
                        />
                    </div>
                    <div>
                        <div className="mb-2 flex items-center gap-1 text-sm text-slate-600">
                            <span>读出顺序 readoutOrder</span>
                            <Tooltip title="描述 FPGA payload 的空间排列。ROW_MAJOR 表示 FPGA 已经按正常行列顺序发送；GLUX1605 HDR 4-lane 表示 payload 仍是 4 个 Sub-LVDS lane 交织顺序，C++ 端会按 Figure 42 规则重排成正常图像。">
                                <Info size={14} className="cursor-help text-slate-400" />
                            </Tooltip>
                        </div>
                        <Select
                            value={connectionForm.readoutOrder}
                            onChange={(value) => actions.setConnectionField("readoutOrder", value)}
                            className="w-full"
                            disabled={connected}
                            options={[
                                {
                                    label: "GLUX1605 HDR 4-lane",
                                    value: "GLUX1605_HDR_4LANE_INTERLEAVED_EFFECTIVE",
                                },
                                { label: "ROW_MAJOR", value: "ROW_MAJOR" },
                            ]}
                        />
                    </div>
                </div>
                {connectionForm.readoutOrder === "GLUX1605_HDR_4LANE_INTERLEAVED_EFFECTIVE"
                    && connectionForm.expectedWidth % 4 !== 0 && (
                        <Alert
                            className="mt-4 rounded-md"
                            type="warning"
                            showIcon
                            message="当前宽度不能用于 GLUX1605 4-lane 重排"
                            description="4-lane 重排需要把每行平均分成 4 个 lane，因此 expectedWidth 必须能被 4 整除。"
                        />
                    )}
            </Card>

            <Card className="bg-white border border-gray-200 shadow-sm">
                <div className="flex flex-col">
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
                                onClick={() => void runAction("reset", () => jniBridgeService.sendReset(), "复位命令已发送")}
                            >
                                复位
                            </Button>
                            <Button
                                icon={<Binary size={16} />}
                                loading={loadingAction === "status"}
                                disabled={!connected || loadingAction !== null}
                                onClick={() => void runAction("status", () => jniBridgeService.queryStatusAndWait(), "状态查询完成")}
                            >
                                查询状态
                            </Button>
                        </div>
                    </div>

                    <div className="grid gap-3 lg:grid-cols-[minmax(0,1.2fr)_minmax(260px,0.8fr)_minmax(220px,0.6fr)]">
                        <div className="rounded-lg border border-slate-200 bg-slate-50 p-4">
                            <Text className="mb-2 block text-sm text-slate-500">状态位</Text>
                            <div className="max-h-24 overflow-auto break-all rounded bg-white p-3 font-mono text-xs leading-relaxed text-slate-800">
                                {latestStatus?.statusBinary || "暂无状态数据"}
                            </div>
                            {latestStatus && (
                                <div className="mt-2 text-xs text-slate-500">
                                    statusBits={latestStatus.statusBits} · errorCode={latestStatus.errorCode}
                                </div>
                            )}
                        </div>

                        <div className="rounded-lg border border-slate-200 bg-slate-50 p-4">
                            <Text className="mb-2 block text-sm text-slate-500">配置应答</Text>
                            {latestConfigAck ? (
                                <div className="font-mono text-sm text-slate-800">
                                    resultCode={latestConfigAck.resultCode} · failedAddr={latestConfigAck.failedAddr}
                                </div>
                            ) : (
                                <Text className="text-slate-500">暂无配置应答</Text>
                            )}
                        </div>

                        <div className="rounded-lg border border-slate-200 bg-slate-50 p-4">
                            <Text className="mb-2 block text-sm text-slate-500">数据库历史帧</Text>
                            <div className="flex items-center gap-2">
                                <Tag color="green">PostgreSQL</Tag>
                                <Text className="text-sm text-slate-700">{imageHistory.length} 帧</Text>
                            </div>
                        </div>
                    </div>
                </div>
            </Card>

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
                            onClick={() => void runAction(
                                "config",
                                () => jniBridgeService.sendFullConfigAndWait(configBytes),
                                "完整配置已下发",
                            )}
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

export default ConfigManagementPage;
