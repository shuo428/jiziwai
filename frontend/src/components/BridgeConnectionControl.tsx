import React, { useEffect, useMemo, useState } from "react";
import { Alert, AutoComplete, Button, Card, Checkbox, Tag, Typography } from "antd";
import { AlertCircle, Plug, Unplug } from "lucide-react";
import { toast } from "sonner";

import { jniBridgeService } from "../service/jniBridgeService";
import { useJNIStore } from "../store/jniStore";

const { Title, Text } = Typography;

interface BridgeConnectionControlProps {
    className?: string;
}

const BridgeConnectionControl: React.FC<BridgeConnectionControlProps> = ({ className = "" }) => {
    const [loadingAction, setLoadingAction] = useState<string | null>(null);
    const { connectionForm, bridgeState, savedConnectionOptions, websocketConnected, error, actions } = useJNIStore();
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

    const hostOptions = useMemo(
        () => savedConnectionOptions.hosts.map((host) => ({ label: host, value: host })),
        [savedConnectionOptions.hosts],
    );
    const controlPortOptions = useMemo(
        () => savedConnectionOptions.controlPorts.map((port) => ({ label: String(port), value: String(port) })),
        [savedConnectionOptions.controlPorts],
    );
    const imagePortOptions = useMemo(
        () => savedConnectionOptions.imagePorts.map((port) => ({ label: String(port), value: String(port) })),
        [savedConnectionOptions.imagePorts],
    );

    const handlePortChange = (field: "controlPort" | "imagePort", value: string) => {
        const digits = value.replace(/[^\d]/g, "");
        const port = digits ? Math.min(65535, Number(digits)) : 0;
        actions.setConnectionField(field, port);
    };

    return (
        <Card className={`bg-white border border-gray-200 shadow-sm ${className}`}>
            <div className="flex flex-wrap items-center justify-between gap-4">
                <div className="flex items-center gap-3">
                    <div className="rounded-lg bg-blue-500 p-2.5">
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
                        <Text className="text-sm text-slate-500">{connectionSummary}</Text>
                    </div>
                </div>

                <div className="flex flex-wrap gap-2">
                    <Button
                        type="primary"
                        icon={<Plug size={16} />}
                        loading={loadingAction === "connect"}
                        disabled={loadingAction !== null || connected}
                        onClick={() => void runAction("connect", () => jniBridgeService.connect(), "连接成功")}
                    >
                        连接
                    </Button>
                    <Button
                        danger
                        icon={<Unplug size={16} />}
                        loading={loadingAction === "disconnect"}
                        disabled={loadingAction !== null || !connected}
                        onClick={() => void runAction("disconnect", () => jniBridgeService.disconnect(), "已断开连接")}
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
                    <AutoComplete
                        className="w-full"
                        value={connectionForm.host}
                        options={hostOptions}
                        onChange={(value) => actions.setConnectionField("host", value)}
                        placeholder="例如 192.168.1.10"
                        disabled={connected}
                        filterOption={(inputValue, option) =>
                            String(option?.value ?? "").toLowerCase().includes(inputValue.toLowerCase())
                        }
                    />
                    {hostOptions.length > 0 && (
                        <Text className="mt-1 block text-[11px] text-slate-400">
                            可下拉选择已保存 Host，也可以直接输入新地址。
                        </Text>
                    )}
                </div>
                <div>
                    <Text className="mb-2 block text-sm text-slate-600">Control Port</Text>
                    <AutoComplete
                        className="w-full"
                        value={connectionForm.controlPort ? String(connectionForm.controlPort) : ""}
                        options={controlPortOptions}
                        onChange={(value) => handlePortChange("controlPort", value)}
                        placeholder="例如 9000"
                        disabled={connected}
                        filterOption={(inputValue, option) =>
                            String(option?.value ?? "").includes(inputValue)
                        }
                    />
                </div>
                <div>
                    <Text className="mb-2 block text-sm text-slate-600">Image Port</Text>
                    <AutoComplete
                        className="w-full"
                        value={connectionForm.imagePort ? String(connectionForm.imagePort) : ""}
                        options={imagePortOptions}
                        onChange={(value) => handlePortChange("imagePort", value)}
                        placeholder="例如 9001"
                        disabled={connected}
                        filterOption={(inputValue, option) =>
                            String(option?.value ?? "").includes(inputValue)
                        }
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
    );
};

export default BridgeConnectionControl;
