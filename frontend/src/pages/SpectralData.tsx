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
import type { ImageFrameRecord } from "../types/jni";

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

const qualityColor = (status?: string | null): string => {
    if (status === "PASS") {
        return "green";
    }
    if (status === "WARNING") {
        return "orange";
    }
    if (status === "FAIL") {
        return "red";
    }
    return "default";
};

const formatNullableNumber = (value: number | null, digits = 2): string =>
    typeof value === "number" ? value.toFixed(digits) : "-";

const formatNullableRatio = (value: number | null): string =>
    typeof value === "number" ? `${(value * 100).toFixed(4)}%` : "-";

type MetricStatus = "PASS" | "WARNING" | "FAIL" | "INFO";

type QualityMetricExplanation = {
    key: string;
    label: string;
    value: string;
    healthyCondition: string;
    unhealthyReason: string;
    reference: string;
    status: MetricStatus;
};

const metricColor = (status: MetricStatus): string => {
    if (status === "PASS") {
        return "green";
    }
    if (status === "WARNING") {
        return "orange";
    }
    if (status === "FAIL") {
        return "red";
    }
    return "blue";
};

const dispositionColor = (status?: string | null): string => {
    if (status === "USE_AS_IS") {
        return "green";
    }
    if (status === "PROCESS_REQUIRED" || status === "MANUAL_REVIEW") {
        return "orange";
    }
    if (status === "RECAPTURE_RECOMMENDED" || status === "REJECTED") {
        return "red";
    }
    return "default";
};

const actionColor = (severity?: string | null): string => {
    if (severity === "FAIL") {
        return "red";
    }
    if (severity === "WARNING") {
        return "orange";
    }
    if (severity === "INFO") {
        return "blue";
    }
    return "default";
};

const getQualityThreshold = (
    frame: ImageFrameRecord | null,
    key: string,
    fallback: number,
): number => {
    const thresholds = frame?.qualityDetails?.thresholds;
    if (thresholds && typeof thresholds === "object" && key in thresholds) {
        const value = (thresholds as Record<string, unknown>)[key];
        if (typeof value === "number") {
            return value;
        }
    }
    return fallback;
};

const getQualityDecisionReasons = (frame: ImageFrameRecord | null): string[] => {
    const reasons = frame?.qualityDetails?.decisionReasons;
    if (Array.isArray(reasons)) {
        return reasons.filter((item): item is string => typeof item === "string");
    }
    return [];
};

const buildQualityMetricExplanations = (frame: ImageFrameRecord | null): QualityMetricExplanation[] => {
    if (!frame) {
        return [];
    }

    const blackWarningRatio = getQualityThreshold(frame, "blackWarningRatio", 0.05);
    const blackFailRatio = getQualityThreshold(frame, "blackFailRatio", 0.5);
    const saturationWarningRatio = getQualityThreshold(frame, "saturationWarningRatio", 0.001);
    const saturationFailRatio = getQualityThreshold(frame, "saturationFailRatio", 0.01);
    const dynamicRangeWarningDn = getQualityThreshold(frame, "dynamicRangeWarningDn", 64);
    const dynamicRangeFailDn = getQualityThreshold(frame, "dynamicRangeFailDn", 16);
    const abnormalLineWarningCount = getQualityThreshold(frame, "abnormalLineWarningCount", 1);
    const abnormalLineFailCount = getQualityThreshold(frame, "abnormalLineFailCount", 3);
    const badPixelWarningCount = getQualityThreshold(frame, "badPixelWarningCount", 60);
    const badPixelFailCount = getQualityThreshold(frame, "badPixelFailCount", 500);

    const dynamicRange =
        typeof frame.pixelMin === "number" && typeof frame.pixelMax === "number"
            ? frame.pixelMax - frame.pixelMin
            : null;
    const abnormalLineCount =
        typeof frame.abnormalRowCount === "number" && typeof frame.abnormalColumnCount === "number"
            ? frame.abnormalRowCount + frame.abnormalColumnCount
            : null;

    const ratioStatus = (value: number | null, warning: number, fail: number): MetricStatus => {
        if (typeof value !== "number") {
            return "INFO";
        }
        if (value >= fail) {
            return "FAIL";
        }
        if (value >= warning) {
            return "WARNING";
        }
        return "PASS";
    };

    const dynamicRangeStatus: MetricStatus =
        typeof dynamicRange !== "number"
            ? "INFO"
            : dynamicRange <= dynamicRangeFailDn
              ? "FAIL"
              : dynamicRange <= dynamicRangeWarningDn
                ? "WARNING"
                : "PASS";

    const abnormalLineStatus: MetricStatus =
        typeof abnormalLineCount !== "number"
            ? "INFO"
            : abnormalLineCount >= abnormalLineFailCount
              ? "FAIL"
              : abnormalLineCount >= abnormalLineWarningCount
                ? "WARNING"
                : "PASS";

    const badPixelStatus: MetricStatus =
        typeof frame.badPixelCount !== "number"
            ? "INFO"
            : frame.badPixelCount >= badPixelFailCount
              ? "FAIL"
              : frame.badPixelCount > badPixelWarningCount
                ? "WARNING"
                : "PASS";

    return [
        {
            key: "blackPixelRatio",
            label: "黑像素比例",
            value: formatNullableRatio(frame.blackPixelRatio),
            healthyCondition: `< ${(blackWarningRatio * 100).toFixed(2)}%`,
            unhealthyReason: "黑像素比例过高通常表示欠曝、遮挡、无光或有效信号不足，会降低后续光谱提取的信噪比。",
            reference: `DN ≤ ${getQualityThreshold(frame, "blackThresholdDn", 16)} 计为黑像素；≥ ${(blackWarningRatio * 100).toFixed(2)}% 警告，≥ ${(blackFailRatio * 100).toFixed(2)}% 失败。`,
            status: ratioStatus(frame.blackPixelRatio, blackWarningRatio, blackFailRatio),
        },
        {
            key: "saturationPixelRatio",
            label: "饱和像素比例",
            value: formatNullableRatio(frame.saturationPixelRatio),
            healthyCondition: `< ${(saturationWarningRatio * 100).toFixed(3)}%`,
            unhealthyReason: "饱和像素过多说明 ADC 或像素输出接近满量程，峰强度和局部细节已经被裁剪，定量信息不可恢复。",
            reference: `DN ≥ ${getQualityThreshold(frame, "saturationThresholdDn", 4080)} 计为饱和；≥ ${(saturationWarningRatio * 100).toFixed(3)}% 警告，≥ ${(saturationFailRatio * 100).toFixed(2)}% 失败。`,
            status: ratioStatus(frame.saturationPixelRatio, saturationWarningRatio, saturationFailRatio),
        },
        {
            key: "dynamicRange",
            label: "动态范围",
            value: typeof dynamicRange === "number" ? `${dynamicRange} DN` : "-",
            healthyCondition: `> ${dynamicRangeWarningDn} DN`,
            unhealthyReason: "动态范围过小表示图像接近常数图，常见于无光、曝光不足、遮挡或读出异常。",
            reference: `pixel_max - pixel_min；≤ ${dynamicRangeWarningDn} DN 警告，≤ ${dynamicRangeFailDn} DN 失败。`,
            status: dynamicRangeStatus,
        },
        {
            key: "abnormalLineCount",
            label: "异常行/列",
            value:
                typeof abnormalLineCount === "number"
                    ? `${abnormalLineCount} 条（行 ${frame.abnormalRowCount ?? "-"} / 列 ${frame.abnormalColumnCount ?? "-"}）`
                    : "-",
            healthyCondition: `< ${abnormalLineWarningCount} 条，理想为 0`,
            unhealthyReason: "孤立异常行/列通常反映行列读出链路、传感器列放大器或固定条纹问题，会污染光谱图像的空间一致性。",
            reference: `按行/列均值相对局部中值检测；GLUX1605BSI Grade 1 对缺陷行/列总数要求为 0。当前 ≥ ${abnormalLineWarningCount} 条警告，≥ ${abnormalLineFailCount} 条失败。`,
            status: abnormalLineStatus,
        },
        {
            key: "badPixelCount",
            label: "坏点数量",
            value: typeof frame.badPixelCount === "number" ? `${frame.badPixelCount} 个` : "-",
            healthyCondition: `≤ ${badPixelWarningCount} 个`,
            unhealthyReason: "坏点过多会在图像中形成孤立亮点/暗点，可能在积分提取光谱时形成假峰或抬高噪声。",
            reference: `用 8 邻域中值 + MAD 做单帧局部离群检测；GLUX1605BSI Grade 1 total defect pixels 限值为 60。当前 > ${badPixelWarningCount} 警告，≥ ${badPixelFailCount} 失败。`,
            status: badPixelStatus,
        },
        {
            key: "grayStats",
            label: "灰度统计",
            value: `${frame.pixelMin ?? "-"} / ${frame.pixelMax ?? "-"} / ${formatNullableNumber(frame.pixelMean)} / σ ${formatNullableNumber(frame.pixelStddev)}`,
            healthyCondition: "辅助指标，不单独决定 PASS/FAIL",
            unhealthyReason: "均值和标准差用于解释整体亮度、噪声和对比度；不同光源/曝光下会自然变化，所以不单独设死阈值。",
            reference: "依次为 min / max / mean / stddev，主要配合黑像素比例、饱和比例和动态范围解释图像状态。",
            status: "INFO",
        },
    ];
};

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

    const qualityMetricExplanations = useMemo(
        () => buildQualityMetricExplanations(currentImage),
        [currentImage],
    );
    const problemQualityMetrics = useMemo(
        () => qualityMetricExplanations.filter((metric) => metric.status === "WARNING" || metric.status === "FAIL"),
        [qualityMetricExplanations],
    );
    const qualityDecisionReasons = useMemo(
        () => getQualityDecisionReasons(currentImage),
        [currentImage],
    );

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

            <div className="grid gap-6 xl:grid-cols-[minmax(0,1.15fr)_minmax(360px,0.85fr)] xl:items-start">
                <Card className="bg-white border border-gray-200 shadow-sm">
                    <div className="flex h-full flex-col">
                        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
                            <div>
                                <Title level={5} className="!mb-0 flex items-center gap-2 !text-slate-800">
                                    <Camera size={18} />
                                    当前图像帧
                                </Title>
                                <Text className="text-xs text-slate-500">显示最新一帧预览，原始 RAW 已保存在服务器</Text>
                            </div>
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

                        <div className="flex min-h-[320px] flex-1 items-center justify-center overflow-hidden rounded-lg border border-slate-200 bg-slate-950 shadow-inner">
                            {currentImage?.imageDataUrl ? (
                                <img
                                    src={currentImage.imageDataUrl}
                                    alt="当前光谱图像"
                                    className="max-h-[500px] max-w-full object-contain"
                                />
                            ) : (
                                <div className="text-center text-slate-400">
                                    <Camera size={42} className="mx-auto mb-3 opacity-60" />
                                    <Text className="text-slate-400">暂无图像帧</Text>
                                </div>
                            )}
                        </div>

                        {currentImage && (
                            <div className="mt-3 grid gap-2 rounded-lg border border-slate-200 bg-slate-50 p-3 text-sm text-slate-600 sm:grid-cols-2 lg:grid-cols-4">
                                <div>
                                    <Text className="block text-xs text-slate-500">尺寸</Text>
                                    <span className="font-medium text-slate-800">
                                        {currentImage.width} x {currentImage.height}
                                    </span>
                                </div>
                                <div>
                                    <Text className="block text-xs text-slate-500">8-bit预览</Text>
                                    <span className="font-medium text-slate-800">{currentImage.raw8Length} bytes</span>
                                </div>
                                <div>
                                    <Text className="block text-xs text-slate-500">接收时间</Text>
                                    <span className="font-medium text-slate-800">
                                        {new Date(currentImage.timestamp).toLocaleString("zh-CN")}
                                    </span>
                                </div>
                                <div>
                                    <Text className="block text-xs text-slate-500">质量状态</Text>
                                    <Tag color={qualityColor(currentImage.qualityStatus)} className="mt-1">
                                        {currentImage.qualityStatus || "NOT_EVALUATED"}
                                    </Tag>
                                </div>
                            </div>
                        )}
                    </div>
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

                        <div className="grid flex-1 gap-3">
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

                            <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-1 2xl:grid-cols-2">
                                <div className="rounded-lg border border-slate-200 bg-slate-50 p-4">
                                    <Text className="mb-2 block text-sm text-slate-500">配置应答</Text>
                                    {latestConfigAck ? (
                                        <div className="font-mono text-sm text-slate-800">
                                            resultCode={latestConfigAck.resultCode} · failedAddr=
                                            {latestConfigAck.failedAddr}
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
                    </div>
                </Card>
            </div>

            <Card className="bg-white border border-gray-200 shadow-sm">
                <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
                    <div>
                        <Title level={5} className="!mb-0 flex items-center gap-2 !text-slate-800">
                            <Activity size={18} />
                            当前帧质量诊断
                        </Title>
                        <Text className="text-xs text-slate-500">
                            展开为横向指标面板，便于快速定位导致图片不合格的原因
                        </Text>
                    </div>
                    <Tag color={qualityColor(currentImage?.qualityStatus)}>
                        {currentImage?.qualityStatus || "NOT_EVALUATED"}
                    </Tag>
                </div>

                {!currentImage ? (
                    <Text className="text-sm text-slate-500">暂无图像，获取一帧后显示质量诊断。</Text>
                ) : (
                    <div className="space-y-4">
                        <div className="rounded-lg border border-blue-200 bg-blue-50 p-3">
                            <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
                                <Text className="text-sm font-medium text-blue-700">质量处置建议</Text>
                                <div className="flex flex-wrap gap-2">
                                    <Tag color={dispositionColor(currentImage.dispositionStatus)} className="m-0">
                                        {currentImage.dispositionStatus || "MANUAL_REVIEW"}
                                    </Tag>
                                    <Tag color={currentImage.usableForSpectral ? "green" : "orange"} className="m-0">
                                        {currentImage.usableForSpectral ? "可进入光谱提取" : "暂不进入光谱提取"}
                                    </Tag>
                                </div>
                            </div>
                            <div className="text-sm leading-relaxed text-slate-600">
                                {currentImage.dispositionMessage || "暂无处置建议，请先完成质量分析。"}
                            </div>
                            {currentImage.recommendedActions.length > 0 && (
                                <div className="mt-3 grid gap-2 md:grid-cols-2 xl:grid-cols-3">
                                    {currentImage.recommendedActions.map((action) => (
                                        <div key={action.code} className="rounded-md bg-white/80 p-2.5 text-xs">
                                            <div className="mb-1 flex flex-wrap items-center gap-2">
                                                <Tag color={actionColor(action.severity)} className="m-0">
                                                    {action.stage}
                                                </Tag>
                                                <span className="font-medium text-slate-800">{action.label}</span>
                                            </div>
                                            <div className="leading-relaxed text-slate-500">{action.reason}</div>
                                        </div>
                                    ))}
                                </div>
                            )}
                        </div>

                        {currentImage.qualityStatus !== "PASS" && (
                            <div className="rounded-lg border border-orange-200 bg-orange-50 p-3">
                                <Text className="block text-sm font-medium text-orange-700">
                                    导致当前状态不是 PASS 的指标
                                </Text>
                                {problemQualityMetrics.length > 0 ? (
                                    <div className="mt-3 grid gap-3 md:grid-cols-2 xl:grid-cols-3">
                                        {problemQualityMetrics.map((metric) => (
                                            <div key={metric.key} className="rounded-md bg-white/80 p-3 text-sm">
                                                <div className="mb-1 flex items-center gap-2">
                                                    <Tag color={metricColor(metric.status)} className="m-0">
                                                        {metric.status}
                                                    </Tag>
                                                    <span className="font-medium text-slate-800">{metric.label}</span>
                                                </div>
                                                <div className="font-mono text-slate-700">当前值 {metric.value}</div>
                                                <div className="mt-1 text-xs leading-relaxed text-slate-500">
                                                    {metric.unhealthyReason}
                                                </div>
                                            </div>
                                        ))}
                                    </div>
                                ) : (
                                    <div className="mt-2 text-sm text-slate-600">
                                        {currentImage.qualitySummaryMessage ||
                                            qualityDecisionReasons.join("；") ||
                                            "后端返回了非PASS状态，但没有提供具体原因。"}
                                    </div>
                                )}
                            </div>
                        )}

                        <div className="grid gap-3 lg:grid-cols-2 2xl:grid-cols-3">
                            {qualityMetricExplanations.map((metric) => (
                                <div key={metric.key} className="rounded-lg border border-slate-200 bg-slate-50 p-3">
                                    <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
                                        <div className="flex items-center gap-2">
                                            <Tag color={metricColor(metric.status)} className="m-0">
                                                {metric.status}
                                            </Tag>
                                            <span className="font-medium text-slate-800">{metric.label}</span>
                                        </div>
                                        <span className="font-mono text-sm text-slate-700">{metric.value}</span>
                                    </div>
                                    <div className="space-y-1 text-xs leading-relaxed text-slate-500">
                                        <div>
                                            <span className="font-medium text-slate-600">合格条件：</span>
                                            {metric.healthyCondition}
                                        </div>
                                        <div>
                                            <span className="font-medium text-slate-600">异常原因：</span>
                                            {metric.unhealthyReason}
                                        </div>
                                        <div>
                                            <span className="font-medium text-slate-600">参考依据：</span>
                                            {metric.reference}
                                        </div>
                                    </div>
                                </div>
                            ))}
                        </div>
                    </div>
                )}
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
