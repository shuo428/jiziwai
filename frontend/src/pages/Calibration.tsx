import React, { useEffect, useRef, useState } from "react";
import { Button, Card, Checkbox, Divider, InputNumber, Select, Space, Table, Tag, Typography } from "antd";
import { Beaker, CircleStop, Eye, RefreshCw, Save, Settings2 } from "lucide-react";
import { toast } from "sonner";

import { jniBridgeService } from "../service/jniBridgeService";
import { useJNIStore } from "../store/jniStore";
import type {
    CalibrationPreviewRecord,
    CalibrationGlobalSettingsRecord,
    CalibrationSessionRecord,
} from "../types/jni";

const { Title, Text } = Typography;

const calibrationTypeOptions = [
    { value: "DARK", label: "暗场 DARK" },
    { value: "FLAT", label: "平场 FLAT" },
] as const;

const statusColor = (status?: string): string => {
    if (status === "READY") return "green";
    if (status === "PROCESSING") return "blue";
    if (status === "FAILED") return "red";
    return "default";
};

const sleep = (milliseconds: number): Promise<void> =>
    new Promise((resolve) => window.setTimeout(resolve, milliseconds));

const CalibrationPage: React.FC = () => {
    const { bridgeState } = useJNIStore();
    const [calibrationType, setCalibrationType] = useState<"DARK" | "FLAT">("DARK");
    const [frameCount, setFrameCount] = useState(8);
    const [intervalMs, setIntervalMs] = useState(1000);
    const [activeDarkCalibrationId, setActiveDarkCalibrationId] = useState<number | null>(null);
    const [activeFlatCalibrationId, setActiveFlatCalibrationId] = useState<number | null>(null);
    const [defectMapEnabled, setDefectMapEnabled] = useState(false);
    const [loadingAction, setLoadingAction] = useState<string | null>(null);
    const [collectedIds, setCollectedIds] = useState<number[]>([]);
    const [sessions, setSessions] = useState<CalibrationSessionRecord[]>([]);
    const [darkPreviewSession, setDarkPreviewSession] = useState<CalibrationSessionRecord | null>(null);
    const [darkPreviews, setDarkPreviews] = useState<CalibrationPreviewRecord[]>([]);
    const [flatPreviewSession, setFlatPreviewSession] = useState<CalibrationSessionRecord | null>(null);
    const [flatPreviews, setFlatPreviews] = useState<CalibrationPreviewRecord[]>([]);
    const [globalSettings, setGlobalSettings] = useState<CalibrationGlobalSettingsRecord | null>(null);
    const stopRef = useRef(false);

    const loadSessions = async (): Promise<void> => {
        const records = (await jniBridgeService.listCalibrations()) ?? [];
        setSessions(records);
        const latestDark = records.find((item) => item.calibrationType === "DARK");
        const latestFlat = records.find((item) => item.calibrationType === "FLAT");
        await Promise.all([
            latestDark ? showCalibrationPreview(latestDark) : Promise.resolve(),
            latestFlat ? showCalibrationPreview(latestFlat) : Promise.resolve(),
        ]);
    };

    const showCalibrationPreview = async (session: CalibrationSessionRecord): Promise<void> => {
        const isDark = session.calibrationType === "DARK";
        if (isDark) {
            setDarkPreviewSession(session);
        } else {
            setFlatPreviewSession(session);
        }
        try {
            const previews = await jniBridgeService.listCalibrationPreviews(session.id, 6);
            if (isDark) {
                setDarkPreviews(previews);
            } else {
                setFlatPreviews(previews);
            }
        } catch (error) {
            if (isDark) {
                setDarkPreviews([]);
            } else {
                setFlatPreviews([]);
            }
            toast.error(error instanceof Error ? error.message : "加载校准图片失败");
        }
    };

    const loadGlobalSettings = async (): Promise<void> => {
        const settings = await jniBridgeService.getCalibrationGlobalSettings();
        setGlobalSettings(settings);
        setActiveDarkCalibrationId(settings.darkCalibrationId);
        setActiveFlatCalibrationId(settings.flatCalibrationId);
        setDefectMapEnabled(settings.defectMapEnabled);
    };

    useEffect(() => {
        loadSessions().catch(() => undefined);
        loadGlobalSettings().catch(() => undefined);
    }, []);

    const collectFrames = async (): Promise<number[]> => {
        if (!bridgeState.connected) {
            throw new Error("请先在获取光谱数据页面连接设备或模拟FPGA");
        }
        const count = Math.max(2, Math.min(64, Math.trunc(frameCount)));
        const ids: number[] = [];
        stopRef.current = false;
        for (let index = 0; index < count; index++) {
            if (stopRef.current) {
                break;
            }
            const frame = await jniBridgeService.triggerOnceAndWaitForFrame({ autoProcess: false });
            ids.push(frame.id);
            setCollectedIds([...ids]);
            if (index < count - 1 && intervalMs > 0) {
                await sleep(Math.max(0, Math.trunc(intervalMs)));
            }
        }
        if (ids.length < 2) {
            throw new Error("有效采集帧少于2张，无法进行多帧分析");
        }
        return ids;
    };

    const handleSimulate = async (): Promise<void> => {
        setLoadingAction("simulate");
        try {
            const session = await jniBridgeService.simulateCalibration(calibrationType, {
                frameCount,
                width: 800,
                height: 600,
            });
            setSessions((current) => [session, ...current.filter((item) => item.id !== session.id)]);
            await showCalibrationPreview(session);
            toast.success(`${calibrationType === "DARK" ? "暗场" : "平场"}模拟数据已保存`);
        } catch (error) {
            toast.error(error instanceof Error ? error.message : "生成模拟校准数据失败");
        } finally {
            setLoadingAction(null);
        }
    };

    const handleCollectCalibration = async (): Promise<void> => {
        setLoadingAction("calibration");
        try {
            const ids = await collectFrames();
            const session = await jniBridgeService.buildCalibrationFromImages(calibrationType, { imageIds: ids });
            setSessions((current) => [session, ...current.filter((item) => item.id !== session.id)]);
            await showCalibrationPreview(session);
            toast.success(`已保存${calibrationType === "DARK" ? "暗场" : "平场"}校准会话，共${ids.length}帧`);
        } catch (error) {
            toast.error(error instanceof Error ? error.message : "连续采集校准数据失败");
        } finally {
            setLoadingAction(null);
        }
    };

    const handleSaveCalibrationPackage = async (): Promise<void> => {
        setLoadingAction("global-settings");
        try {
            const settings = await jniBridgeService.updateCalibrationGlobalSettings({
                enabled: true,
                darkCalibrationId: activeDarkCalibrationId,
                flatCalibrationId: activeFlatCalibrationId,
                defectMapEnabled,
            });
            setGlobalSettings(settings);
            setActiveDarkCalibrationId(settings.darkCalibrationId);
            setActiveFlatCalibrationId(settings.flatCalibrationId);
            setDefectMapEnabled(settings.defectMapEnabled);
            toast.success("当前校准包已保存并启用");
        } catch (error) {
            toast.error(error instanceof Error ? error.message : "保存校准包失败");
        } finally {
            setLoadingAction(null);
        }
    };

    const handleDisableCalibrationPackage = async (): Promise<void> => {
        setLoadingAction("global-settings-disable");
        try {
            const settings = await jniBridgeService.updateCalibrationGlobalSettings({
                enabled: false,
                darkCalibrationId: activeDarkCalibrationId,
                flatCalibrationId: activeFlatCalibrationId,
                defectMapEnabled: false,
            });
            setGlobalSettings(settings);
            setActiveDarkCalibrationId(settings.darkCalibrationId);
            setActiveFlatCalibrationId(settings.flatCalibrationId);
            setDefectMapEnabled(settings.defectMapEnabled);
            toast.success("校准包已停用，后续采集将沿用原始 RAW 流程");
        } catch (error) {
            toast.error(error instanceof Error ? error.message : "停用校准包失败");
        } finally {
            setLoadingAction(null);
        }
    };

    return (
        <div className="space-y-5">
            <div>
                <Title level={3} className="!mb-1 !text-slate-800">暗场、平场与稳定缺陷修复校准</Title>
                <Text className="text-slate-500">
                    校准数据独立保存；当前可生成模拟数据，也可连续采集真实 CMOS 帧进行替换。
                </Text>
            </div>

            <Card size="small" className="border border-cyan-200 bg-cyan-50">
                <div className="flex flex-wrap items-center justify-between gap-4">
                    <div>
                        <Text className="block font-medium text-slate-800">当前校准包与稳定缺陷修复</Text>
                        <Text className="mt-1 block text-xs leading-5 text-slate-600">
                            {globalSettings?.message || "正在读取当前用户的全局校准状态…"}
                        </Text>
                        <div className="mt-2 flex flex-wrap gap-2">
                            <Tag color={globalSettings?.enabled ? "green" : "default"}>
                                {globalSettings?.enabled ? "全局已启用" : "全局未启用"}
                            </Tag>
                            <Tag color={globalSettings?.calibrationPackageReady ? "green" : "default"}>
                                校准包 {globalSettings?.calibrationPackageReady ? "READY" : "未就绪"}
                            </Tag>
                            <Tag color={globalSettings?.defectMapAvailable ? "green" : "default"}>
                                稳定缺陷地图 {globalSettings?.defectMapAvailable ? "可用" : "无有效条目"}
                            </Tag>
                        </div>
                    </div>
                </div>
                <div className="mt-4 grid gap-3 md:grid-cols-2 xl:grid-cols-4">
                    <Select
                        value={activeDarkCalibrationId ?? undefined}
                        placeholder="选择 READY 暗场会话"
                        options={sessions
                            .filter((item) => item.calibrationType === "DARK" && item.status === "READY")
                            .map((item) => ({ value: item.id, label: `暗场 D-${String(item.sessionNumber).padStart(3, "0")} · ${item.width}×${item.height}` }))}
                        disabled={loadingAction !== null}
                        onChange={(value) => setActiveDarkCalibrationId(typeof value === "number" ? value : null)}
                    />
                    <Select
                        value={activeFlatCalibrationId ?? undefined}
                        placeholder="选择 READY 平场会话"
                        options={sessions
                            .filter((item) => item.calibrationType === "FLAT" && item.status === "READY")
                            .map((item) => ({ value: item.id, label: `平场 F-${String(item.sessionNumber).padStart(3, "0")} · ${item.width}×${item.height}` }))}
                        disabled={loadingAction !== null}
                        onChange={(value) => setActiveFlatCalibrationId(typeof value === "number" ? value : null)}
                    />
                    <div className="flex items-center gap-3">
                        <Checkbox
                            checked={defectMapEnabled}
                            onChange={(event) => setDefectMapEnabled(event.target.checked)}
                            disabled={loadingAction !== null}
                        >
                            启用稳定缺陷修复
                        </Checkbox>
                        <Text className="text-xs text-slate-500">保存启用后生效</Text>
                    </div>
                    <Space wrap>
                        <Button
                            type="primary"
                            loading={loadingAction === "global-settings"}
                            disabled={loadingAction !== null || Boolean(globalSettings?.enabled)}
                            title={globalSettings?.enabled ? "当前校准包已启用，如需重新保存请先停用校准包" : "保存并启用当前选择的校准包"}
                            onClick={() => void handleSaveCalibrationPackage()}
                        >
                            保存并启用校准包
                        </Button>
                        <Button
                            danger
                            loading={loadingAction === "global-settings-disable"}
                            disabled={loadingAction !== null || !globalSettings?.enabled}
                            onClick={() => void handleDisableCalibrationPackage()}
                        >
                            停用校准包
                        </Button>
                    </Space>
                </div>
            </Card>

            <div>
                <Card title={<span className="flex items-center gap-2"><Beaker size={18} />校准数据采集与保存</span>}>
                    <div className="grid gap-4 md:grid-cols-2">
                        <div>
                            <Text className="mb-2 block text-sm text-slate-600">校准类型</Text>
                            <Select
                                className="w-full"
                                value={calibrationType}
                                options={[...calibrationTypeOptions]}
                                onChange={setCalibrationType}
                                disabled={loadingAction !== null}
                            />
                        </div>
                        <div>
                            <Text className="mb-2 block text-sm text-slate-600">采集帧数</Text>
                            <InputNumber
                                className="w-full"
                                min={2}
                                max={64}
                                value={frameCount}
                                onChange={(value) => setFrameCount(Number(value ?? 2))}
                                disabled={loadingAction !== null}
                            />
                        </div>
                        <div>
                            <Text className="mb-2 block text-sm text-slate-600">帧间隔（毫秒）</Text>
                            <InputNumber
                                className="w-full"
                                min={0}
                                max={60000}
                                value={intervalMs}
                                onChange={(value) => setIntervalMs(Number(value ?? 0))}
                                disabled={loadingAction !== null}
                            />
                            <Text className="mt-1 block text-xs text-slate-400">真实使用可设置为 8000；测试时可设置为 0。</Text>
                        </div>
                        <div>
                            <Text className="mb-2 block text-sm text-slate-600">当前设备</Text>
                            <Tag color={bridgeState.connected ? "green" : "default"}>
                                {bridgeState.connected ? "已连接，可硬件采集" : "未连接，仅可生成模拟数据"}
                            </Tag>
                        </div>
                    </div>

                    <Divider />
                    <Space wrap>
                        <Button
                            icon={<Settings2 size={16} />}
                            loading={loadingAction === "simulate"}
                            disabled={loadingAction !== null}
                            onClick={handleSimulate}
                        >
                            生成模拟{calibrationType === "DARK" ? "暗场" : "平场"}
                        </Button>
                        <Button
                            type="primary"
                            icon={<Save size={16} />}
                            loading={loadingAction === "calibration"}
                            disabled={!bridgeState.connected || loadingAction !== null}
                            onClick={handleCollectCalibration}
                        >
                            连续采集并保存校准
                        </Button>
                        {loadingAction !== null && loadingAction !== "simulate" && (
                            <Button danger icon={<CircleStop size={16} />} onClick={() => { stopRef.current = true; }}>
                                停止后续采集
                            </Button>
                        )}
                    </Space>
                    <Text className="mt-3 block text-xs text-slate-500">
                        最近采集到的帧：{collectedIds.length ? collectedIds.join(", ") : "暂无"}
                    </Text>
                </Card>

            </div>

            <div className="grid gap-5 xl:grid-cols-2">
                <Card title={<span className="flex items-center gap-2"><RefreshCw size={18} />暗场校准会话</span>}>
                    <Table<CalibrationSessionRecord>
                        rowKey="id"
                        size="small"
                        pagination={{ pageSize: 6, showSizeChanger: false }}
                        dataSource={sessions.filter((item) => item.calibrationType === "DARK")}
                        columns={[
                            { title: "暗场编号", dataIndex: "sessionNumber", render: (value: number) => `D-${String(value).padStart(3, "0")}` },
                            { title: "状态", dataIndex: "status", render: (value: string) => <Tag color={statusColor(value)}>{value}</Tag> },
                            { title: "帧数", dataIndex: "frameCount" },
                            { title: "坏点", dataIndex: "badPixelCount" },
                            { title: "预览", key: "preview", render: (_: unknown, record: CalibrationSessionRecord) => <Button size="small" icon={<Eye size={14} />} onClick={() => void showCalibrationPreview(record)}>查看</Button> },
                        ]}
                        locale={{ emptyText: "还没有暗场校准会话" }}
                    />
                    <div className="mt-4 rounded-lg border border-slate-200 bg-slate-50 p-3">
                        <Text className="mb-2 block text-sm font-medium text-slate-700">
                            暗场预览 {darkPreviewSession ? `· D-${String(darkPreviewSession.sessionNumber).padStart(3, "0")}` : ""}
                        </Text>
                        {darkPreviews.length > 0 ? <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">{darkPreviews.map((preview) => <img key={preview.frameIndex} src={preview.imageDataUrl} alt={`暗场第${preview.frameIndex}帧`} className="aspect-[4/3] w-full rounded border border-slate-200 bg-slate-950 object-contain" />)}</div> : <Text className="text-xs text-slate-400">请选择一个暗场会话查看预览。</Text>}
                    </div>
                </Card>

                <Card title={<span className="flex items-center gap-2"><RefreshCw size={18} />平场校准会话</span>}>
                    <Table<CalibrationSessionRecord>
                        rowKey="id"
                        size="small"
                        pagination={{ pageSize: 6, showSizeChanger: false }}
                        dataSource={sessions.filter((item) => item.calibrationType === "FLAT")}
                        columns={[
                            { title: "平场编号", dataIndex: "sessionNumber", render: (value: number) => `F-${String(value).padStart(3, "0")}` },
                            { title: "状态", dataIndex: "status", render: (value: string) => <Tag color={statusColor(value)}>{value}</Tag> },
                            { title: "帧数", dataIndex: "frameCount" },
                            { title: "坏点", dataIndex: "badPixelCount" },
                            { title: "预览", key: "preview", render: (_: unknown, record: CalibrationSessionRecord) => <Button size="small" icon={<Eye size={14} />} onClick={() => void showCalibrationPreview(record)}>查看</Button> },
                        ]}
                        locale={{ emptyText: "还没有平场校准会话" }}
                    />
                    <div className="mt-4 rounded-lg border border-slate-200 bg-slate-50 p-3">
                        <Text className="mb-2 block text-sm font-medium text-slate-700">
                            平场预览 {flatPreviewSession ? `· F-${String(flatPreviewSession.sessionNumber).padStart(3, "0")}` : ""}
                        </Text>
                        {flatPreviews.length > 0 ? <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">{flatPreviews.map((preview) => <img key={preview.frameIndex} src={preview.imageDataUrl} alt={`平场第${preview.frameIndex}帧`} className="aspect-[4/3] w-full rounded border border-slate-200 bg-slate-950 object-contain" />)}</div> : <Text className="text-xs text-slate-400">请选择一个平场会话查看预览。</Text>}
                    </div>
                </Card>
            </div>
        </div>
    );
};

export default CalibrationPage;
