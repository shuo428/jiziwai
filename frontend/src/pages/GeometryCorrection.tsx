import React, { useEffect, useMemo, useState } from "react";
import { Alert, Button, Card, Checkbox, Empty, Input, InputNumber, Modal, Select, Space, Spin, Table, Tag, Typography } from "antd";
import { Activity, Compass, Image as ImageIcon, Save, ScanLine, SlidersHorizontal } from "lucide-react";
import { toast } from "sonner";

import ImageVersionPreview from "../components/ImageVersionPreview";
import SpectrumCurve from "../components/SpectrumCurve";
import { jniBridgeService } from "../service/jniBridgeService";
import { useJNIStore } from "../store/jniStore";
import type {
    GeometryCorrectionRecord,
    GeometryCorrectionRequest,
    GeometryProfileRecord,
    ImageFrameRecord,
    SpectrumExtractionRecord,
    SpectrumRoi,
} from "../types/jni";

const { Title, Text } = Typography;

type AxisMode = "AUTO" | "X" | "Y";
type SourceMode = "AUTO" | "ORIGINAL" | "CALIBRATED" | "PROCESSED";
type RotateMode = 0 | 90 | 180 | 270;

const sourceOptions = [
    { value: "AUTO", label: "推荐自动：处理后/校准后/原图" },
    { value: "PROCESSED", label: "处理后 PASS 图" },
    { value: "CALIBRATED", label: "校准后 PASS 图" },
    { value: "ORIGINAL", label: "原始 PASS 图" },
];

const axisOptions = [
    { value: "AUTO", label: "自动判断" },
    { value: "X", label: "X 横向为波长方向" },
    { value: "Y", label: "Y 纵向为波长方向" },
];

const rotateOptions = [
    { value: 0, label: "0°" },
    { value: 90, label: "90°顺时针" },
    { value: 180, label: "180°" },
    { value: 270, label: "270°顺时针" },
];

const sourceModeText = (value?: string | null): string => {
    switch (value) {
        case "GEOMETRY_CORRECTED":
            return "几何校正后";
        case "PROCESSED":
            return "处理后";
        case "CALIBRATED":
            return "校准后";
        case "ORIGINAL":
            return "原图";
        case "AUTO":
            return "自动";
        default:
            return value || "-";
    }
};

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

const emptyRoiDraft = (): Partial<SpectrumRoi> => ({
    xStart: undefined,
    xEnd: undefined,
    yStart: undefined,
    yEnd: undefined,
});

const sanitizeRoiDraft = (roi: Partial<SpectrumRoi>): GeometryCorrectionRequest["roi"] => {
    const output: Partial<SpectrumRoi> = {};
    (["xStart", "xEnd", "yStart", "yEnd"] as const).forEach((key) => {
        const value = roi[key];
        if (typeof value === "number" && Number.isFinite(value)) {
            output[key] = Math.max(0, Math.trunc(value));
        }
    });
    return Object.keys(output).length > 0 ? output : undefined;
};

const profileRoiToDraft = (roi?: SpectrumRoi | null): Partial<SpectrumRoi> => {
    if (!roi || (roi.xStart === 0 && roi.xEnd === 0 && roi.yStart === 0 && roi.yEnd === 0)) {
        return emptyRoiDraft();
    }
    return {
        xStart: roi.xStart,
        xEnd: roi.xEnd,
        yStart: roi.yStart,
        yEnd: roi.yEnd,
    };
};

const formatDate = (value?: string | null): string => {
    if (!value) {
        return "-";
    }
    return new Date(value).toLocaleString("zh-CN");
};

const GeometryCorrectionPage: React.FC = () => {
    const { workMode, imageHistory, hdrImageHistory } = useJNIStore();
    const modeType = workMode === "HDR" ? "HDR" : "NORMAL";
    const storeFrames = modeType === "HDR" ? hdrImageHistory : imageHistory;

    const [frames, setFrames] = useState<ImageFrameRecord[]>(storeFrames);
    const [profiles, setProfiles] = useState<GeometryProfileRecord[]>([]);
    const [selectedImageId, setSelectedImageId] = useState<number | null>(storeFrames[0]?.id ?? null);
    const [selectedProfileId, setSelectedProfileId] = useState<number | null>(null);
    const [loading, setLoading] = useState(false);
    const [runningAction, setRunningAction] = useState<string | null>(null);
    const [profileName, setProfileName] = useState(`${modeType}几何校正配置`);
    const [sourceMode, setSourceMode] = useState<SourceMode>("AUTO");
    const [dispersionAxis, setDispersionAxis] = useState<AxisMode>("AUTO");
    const [rotateDegrees, setRotateDegrees] = useState<RotateMode>(0);
    const [flipX, setFlipX] = useState(false);
    const [flipY, setFlipY] = useState(false);
    const [tiltCorrectionEnabled, setTiltCorrectionEnabled] = useState(true);
    const [maxShiftPixels, setMaxShiftPixels] = useState<number | null>(null);
    const [roiDraft, setRoiDraft] = useState<Partial<SpectrumRoi>>(emptyRoiDraft);
    const [analysisResult, setAnalysisResult] = useState<GeometryCorrectionRecord | null>(null);
    const [correctionResult, setCorrectionResult] = useState<GeometryCorrectionRecord | null>(null);
    const [spectrumResult, setSpectrumResult] = useState<SpectrumExtractionRecord | null>(null);
    const [zoomOpen, setZoomOpen] = useState(false);

    const selectedFrame = useMemo(
        () => frames.find((frame) => frame.id === selectedImageId) ?? null,
        [frames, selectedImageId],
    );
    const effectiveWidth = selectedFrame
        ? rotateDegrees === 90 || rotateDegrees === 270
            ? selectedFrame.height
            : selectedFrame.width
        : undefined;
    const effectiveHeight = selectedFrame
        ? rotateDegrees === 90 || rotateDegrees === 270
            ? selectedFrame.width
            : selectedFrame.height
        : undefined;

    const canRunGeometry = Boolean(selectedFrame);

    const applyProfileToForm = (profile: GeometryProfileRecord | null) => {
        if (!profile) {
            setSelectedProfileId(null);
            setProfileName(`${modeType}几何校正配置`);
            setSourceMode("AUTO");
            setDispersionAxis("AUTO");
            setRotateDegrees(0);
            setFlipX(false);
            setFlipY(false);
            setTiltCorrectionEnabled(true);
            setMaxShiftPixels(null);
            setRoiDraft(emptyRoiDraft());
            return;
        }
        setSelectedProfileId(profile.id);
        setProfileName(profile.profileName || `${modeType}几何校正配置`);
        setSourceMode(["AUTO", "ORIGINAL", "CALIBRATED", "PROCESSED"].includes(profile.sourceMode)
            ? (profile.sourceMode as SourceMode)
            : "AUTO");
        setDispersionAxis(["AUTO", "X", "Y"].includes(profile.dispersionAxis)
            ? (profile.dispersionAxis as AxisMode)
            : "AUTO");
        setRotateDegrees([0, 90, 180, 270].includes(profile.rotateDegrees)
            ? (profile.rotateDegrees as RotateMode)
            : 0);
        setFlipX(profile.flipX);
        setFlipY(profile.flipY);
        setTiltCorrectionEnabled(profile.tiltCorrectionEnabled);
        setMaxShiftPixels(profile.maxShiftPixels);
        setRoiDraft(profileRoiToDraft(profile.roi));
    };

    const loadPageData = async () => {
        setLoading(true);
        try {
            const [loadedFrames, loadedProfiles] = await Promise.all([
                modeType === "HDR"
                    ? jniBridgeService.loadHdrImageHistory()
                    : jniBridgeService.loadImageHistory(),
                jniBridgeService.listGeometryProfiles(modeType),
            ]);
            setFrames(loadedFrames);
            setProfiles(loadedProfiles);
            const preferredImageId = selectedImageId && loadedFrames.some((frame) => frame.id === selectedImageId)
                ? selectedImageId
                : loadedFrames[0]?.id ?? null;
            setSelectedImageId(preferredImageId);
            const enabledProfile = loadedProfiles.find((profile) => profile.enabled) ?? null;
            applyProfileToForm(enabledProfile);
        } catch (error) {
            toast.error(error instanceof Error ? error.message : "加载几何校正数据失败");
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        setFrames(storeFrames);
    }, [storeFrames]);

    useEffect(() => {
        setProfileName(`${modeType}几何校正配置`);
        setSelectedImageId(storeFrames[0]?.id ?? null);
        setAnalysisResult(null);
        setCorrectionResult(null);
        setSpectrumResult(null);
        loadPageData();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [modeType]);

    useEffect(() => {
        setAnalysisResult(null);
        setCorrectionResult(null);
        setSpectrumResult(null);
        setZoomOpen(false);
        if (!selectedImageId) {
            return;
        }
        jniBridgeService
            .getLatestGeometryCorrection(selectedImageId)
            .then((result) => setCorrectionResult(result))
            .catch(() => {
                setCorrectionResult(null);
            });
        jniBridgeService
            .getLatestSpectrum(selectedImageId)
            .then((result) => setSpectrumResult(result))
            .catch(() => {
                setSpectrumResult(null);
            });
    }, [selectedImageId]);

    const buildRequest = (saveAsEnabledProfile = false): GeometryCorrectionRequest => ({
        profileId: selectedProfileId,
        useEnabledProfile: false,
        modeType,
        profileName,
        sourceMode,
        dispersionAxis,
        rotateDegrees,
        flipX,
        flipY,
        tiltCorrectionEnabled,
        maxShiftPixels,
        roi: sanitizeRoiDraft(roiDraft),
        saveAsEnabledProfile,
    });

    const runAction = async (
        actionKey: string,
        task: () => Promise<void>,
    ) => {
        setRunningAction(actionKey);
        try {
            await task();
        } catch (error) {
            toast.error(error instanceof Error ? error.message : "操作失败");
        } finally {
            setRunningAction(null);
        }
    };

    const handleAnalyze = () => {
        if (!selectedFrame) {
            toast.warning("请先选择一张图像");
            return;
        }
        runAction("analyze", async () => {
            setCorrectionResult(null);
            setSpectrumResult(null);
            setZoomOpen(false);
            const result = await jniBridgeService.analyzeGeometryCorrection(selectedFrame.id, buildRequest(false));
            setAnalysisResult(result);
            toast.success(result.summaryMessage || "几何校正分析完成");
        });
    };

    const handleCorrect = (saveAsEnabledProfile = false) => {
        if (!selectedFrame) {
            toast.warning("请先选择一张图像");
            return;
        }
        runAction(saveAsEnabledProfile ? "correct-save-profile" : "correct", async () => {
            const result = await jniBridgeService.correctGeometry(selectedFrame.id, buildRequest(saveAsEnabledProfile));
            setCorrectionResult(result);
            setAnalysisResult(result);
            setSpectrumResult(null);
            toast.success(result.summaryMessage || "几何校正结果已保存");
            const loadedProfiles = await jniBridgeService.listGeometryProfiles(modeType);
            setProfiles(loadedProfiles);
            const enabled = loadedProfiles.find((profile) => profile.enabled) ?? null;
            if (saveAsEnabledProfile) {
                applyProfileToForm(enabled);
            }
        });
    };

    const handleSaveProfileOnly = () => {
        runAction("save-profile", async () => {
            const saved = await jniBridgeService.saveGeometryProfile({
                id: selectedProfileId,
                profileName,
                modeType,
                sourceMode,
                dispersionAxis,
                rotateDegrees,
                flipX,
                flipY,
                tiltCorrectionEnabled,
                maxShiftPixels,
                roi: sanitizeRoiDraft(roiDraft),
                enabled: true,
                details: {
                    savedFrom: "geometry-correction-page",
                },
            });
            const loadedProfiles = await jniBridgeService.listGeometryProfiles(modeType);
            setProfiles(loadedProfiles);
            applyProfileToForm(saved);
            toast.success("几何校正配置已保存并设为默认");
        });
    };

    const handleDeleteProfile = (profileId: number) => {
        Modal.confirm({
            title: "删除几何校正配置",
            content: "删除配置不会删除已经生成的几何校正图像，但后续不能再按该配置追溯参数。",
            okText: "删除",
            cancelText: "取消",
            okButtonProps: { danger: true },
            onOk: async () => {
                await runAction("delete-profile", async () => {
                    await jniBridgeService.deleteGeometryProfile(profileId);
                    const loadedProfiles = await jniBridgeService.listGeometryProfiles(modeType);
                    setProfiles(loadedProfiles);
                    if (selectedProfileId === profileId) {
                        applyProfileToForm(loadedProfiles.find((profile) => profile.enabled) ?? null);
                    }
                    toast.success("几何校正配置已删除");
                });
            },
        });
    };

    const handleExtractSpectrum = () => {
        if (!selectedFrame || !correctionResult?.id) {
            toast.warning("请先保存几何校正结果");
            return;
        }
        runAction("extract-spectrum", async () => {
            const result = await jniBridgeService.extractSpectrum(selectedFrame.id, {
                sourceMode: "GEOMETRY_CORRECTED",
                useGeometryCorrection: true,
                wavelengthAxis: correctionResult.dispersionAxis === "Y" ? "Y" : "X",
                rectifyTilt: false,
                integrationMethod: "MEAN",
                roi: correctionResult.roi,
            });
            setSpectrumResult(result);
            toast.success(result.summaryMessage || "已基于几何校正图提取一维光谱");
        });
    };

    const metricResult = analysisResult ?? correctionResult;

    return (
        <div className="space-y-6">
            <Card className="border border-slate-200 shadow-sm">
                <div className="flex flex-wrap items-start justify-between gap-3">
                    <div>
                        <Title level={4} className="!mb-1 flex items-center gap-2 !text-slate-800">
                            <ScanLine size={20} />
                            光谱几何校正
                        </Title>
                        <Text className="text-sm text-slate-500">
                            当前工作流：{modeType === "HDR" ? "HDR融合图" : "普通单帧图"}。先把二维谱图方向和轻微倾斜处理清楚，再进入一维光谱提取。
                        </Text>
                    </div>
                    <Space wrap>
                        <Tag color={modeType === "HDR" ? "purple" : "blue"}>{modeType}</Tag>
                        <Button onClick={loadPageData} loading={loading}>
                            刷新
                        </Button>
                    </Space>
                </div>
                <Alert
                    className="mt-4"
                    type="info"
                    showIcon
                    message="推荐使用顺序"
                    description={
                        <div className="mt-1 grid gap-2 md:grid-cols-2 xl:grid-cols-4">
                            <div className="rounded-lg border border-blue-100 bg-white/70 p-3">
                                <Text className="block text-xs font-semibold text-slate-700">1. 选择图像</Text>
                                <Text className="text-xs text-slate-500">
                                    优先选择质量为 PASS 的普通单帧图或 HDR 融合图。
                                </Text>
                            </div>
                            <div className="rounded-lg border border-blue-100 bg-white/70 p-3">
                                <Text className="block text-xs font-semibold text-slate-700">2. 设置方向与 ROI</Text>
                                <Text className="text-xs text-slate-500">
                                    不确定方向时保持自动；ROI 留空表示使用整图。
                                </Text>
                            </div>
                            <div className="rounded-lg border border-blue-100 bg-white/70 p-3">
                                <Text className="block text-xs font-semibold text-slate-700">3. 先预分析再生成</Text>
                                <Text className="text-xs text-slate-500">
                                    先看波长方向、偏移范围和置信度，再保存校正图。
                                </Text>
                            </div>
                            <div className="rounded-lg border border-blue-100 bg-white/70 p-3">
                                <Text className="block text-xs font-semibold text-slate-700">4. 提取一维光谱</Text>
                                <Text className="text-xs text-slate-500">
                                    几何校正图保存后，可直接基于它生成一维光谱。
                                </Text>
                            </div>
                        </div>
                    }
                />
            </Card>

            <div className="grid gap-6 xl:grid-cols-[minmax(0,1.05fr)_minmax(420px,0.95fr)]">
                <Card className="border border-slate-200 shadow-sm">
                    <div className="mb-4 flex items-center justify-between gap-3">
                        <div>
                            <Title level={5} className="!mb-0 flex items-center gap-2 !text-slate-800">
                                <ImageIcon size={18} />
                                选择待校正图像
                            </Title>
                            <Text className="text-xs text-slate-500">只显示当前工作模式下的历史图像。</Text>
                        </div>
                        <Tag color={frames.length > 0 ? "green" : "default"}>{frames.length} 张</Tag>
                    </div>

                    {loading ? (
                        <div className="flex min-h-[260px] items-center justify-center">
                            <Spin tip="正在加载图像和配置" />
                        </div>
                    ) : frames.length === 0 ? (
                        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无历史图像，请先完成采集" />
                    ) : (
                        <Table<ImageFrameRecord>
                            rowKey="id"
                            size="small"
                            pagination={{ pageSize: 6 }}
                            dataSource={frames}
                            rowSelection={{
                                type: "radio",
                                selectedRowKeys: selectedImageId ? [selectedImageId] : [],
                                onChange: (keys) => setSelectedImageId(Number(keys[0])),
                            }}
                            onRow={(record) => ({
                                onClick: () => setSelectedImageId(record.id),
                            })}
                            columns={[
                                {
                                    title: "图像ID",
                                    dataIndex: "id",
                                    width: 90,
                                },
                                {
                                    title: "尺寸",
                                    render: (_, record) => `${record.width}×${record.height}`,
                                    width: 110,
                                },
                                {
                                    title: "质量",
                                    render: (_, record) => (
                                        <Tag color={qualityColor(record.qualityStatus)}>
                                            {record.qualityStatus || "NOT_EVALUATED"}
                                        </Tag>
                                    ),
                                    width: 110,
                                },
                                {
                                    title: "采集时间",
                                    render: (_, record) => formatDate(record.timestamp),
                                },
                            ]}
                        />
                    )}

                    <div className="mt-4">
                        <ImageVersionPreview
                            frame={selectedFrame}
                            defaultVersion={
                                selectedFrame?.processedImageDataUrl
                                    ? "processed"
                                    : selectedFrame?.calibratedImageDataUrl
                                      ? "calibrated"
                                      : "raw"
                            }
                            emptyText="请选择一张图像"
                            imageAreaClassName="min-h-[320px]"
                        />
                    </div>
                    {selectedFrame && selectedFrame.qualityStatus !== "PASS" && (
                        <Alert
                            className="mt-3"
                            type="warning"
                            showIcon
                            message="当前图像原始质量不是 PASS"
                            description="几何校正会优先尝试使用处理后或校准后的 PASS 图；如果这张图没有可用的 PASS 版本，执行时会被系统拦截。"
                        />
                    )}
                </Card>

                <Card className="border border-slate-200 shadow-sm">
                    <div className="mb-4 flex items-center justify-between gap-3">
                        <div>
                            <Title level={5} className="!mb-0 flex items-center gap-2 !text-slate-800">
                                <SlidersHorizontal size={18} />
                                几何校正参数
                            </Title>
                            <Text className="text-xs text-slate-500">参数保存为配置后，后续同模式图像可以复用。</Text>
                        </div>
                        <Tag color={selectedProfileId ? "cyan" : "default"}>
                            {selectedProfileId ? `配置 #${selectedProfileId}` : "临时参数"}
                        </Tag>
                    </div>

                    <div className="space-y-4">
                        <div>
                            <Text className="mb-1 block text-xs text-slate-500">已保存配置</Text>
                            <Select
                                allowClear
                                value={selectedProfileId ?? undefined}
                                className="w-full"
                                placeholder="选择历史几何校正配置"
                                onChange={(value) =>
                                    applyProfileToForm(profiles.find((profile) => profile.id === value) ?? null)
                                }
                                options={profiles.map((profile) => ({
                                    value: profile.id,
                                    label: `${profile.enabled ? "默认 · " : ""}${profile.profileName} #${profile.id}`,
                                }))}
                            />
                        </div>

                        <div>
                            <Text className="mb-1 block text-xs text-slate-500">配置名称</Text>
                            <Input value={profileName} onChange={(event) => setProfileName(event.target.value)} />
                        </div>

                        <div className="grid gap-3 md:grid-cols-2">
                            <div>
                                <Text className="mb-1 block text-xs text-slate-500">输入图像版本</Text>
                                <Select<SourceMode>
                                    value={sourceMode}
                                    className="w-full"
                                    options={sourceOptions}
                                    onChange={setSourceMode}
                                />
                            </div>
                            <div>
                                <Text className="mb-1 block text-xs text-slate-500">波长方向</Text>
                                <Select<AxisMode>
                                    value={dispersionAxis}
                                    className="w-full"
                                    options={axisOptions}
                                    onChange={setDispersionAxis}
                                />
                            </div>
                            <div>
                                <Text className="mb-1 block text-xs text-slate-500">旋转</Text>
                                <Select<RotateMode>
                                    value={rotateDegrees}
                                    className="w-full"
                                    options={rotateOptions}
                                    onChange={setRotateDegrees}
                                />
                            </div>
                            <div>
                                <Text className="mb-1 block text-xs text-slate-500">最大倾斜偏移</Text>
                                <InputNumber
                                    value={maxShiftPixels}
                                    min={0}
                                    max={200}
                                    className="w-full"
                                    placeholder="自动"
                                    addonAfter="px"
                                    onChange={(value) => setMaxShiftPixels(typeof value === "number" ? value : null)}
                                />
                            </div>
                        </div>

                        <div className="flex flex-wrap gap-4 rounded-lg border border-slate-200 bg-slate-50 p-3">
                            <Checkbox checked={flipX} onChange={(event) => setFlipX(event.target.checked)}>
                                水平翻转
                            </Checkbox>
                            <Checkbox checked={flipY} onChange={(event) => setFlipY(event.target.checked)}>
                                垂直翻转
                            </Checkbox>
                            <Checkbox
                                checked={tiltCorrectionEnabled}
                                onChange={(event) => setTiltCorrectionEnabled(event.target.checked)}
                            >
                                启用轻微倾斜矫正
                            </Checkbox>
                        </div>

                        <div>
                            <div className="mb-2 flex items-center justify-between gap-2">
                                <Text className="text-xs font-medium text-slate-600">有效光谱区域 ROI</Text>
                                <Button size="small" onClick={() => setRoiDraft(emptyRoiDraft())}>
                                    使用整图
                                </Button>
                            </div>
                            <div className="grid gap-3 md:grid-cols-4">
                                {(["xStart", "xEnd", "yStart", "yEnd"] as const).map((key) => (
                                    <div key={key}>
                                        <Text className="mb-1 block text-xs text-slate-500">{key}</Text>
                                        <InputNumber
                                            value={roiDraft[key]}
                                            min={0}
                                            max={key.startsWith("x") ? effectiveWidth : effectiveHeight}
                                            className="w-full"
                                            placeholder={
                                                !selectedFrame
                                                    ? "-"
                                                    : key === "xStart"
                                                      ? "0"
                                                      : key === "xEnd"
                                                        ? String(effectiveWidth)
                                                        : key === "yStart"
                                                          ? "0"
                                                          : String(effectiveHeight)
                                            }
                                            onChange={(value) =>
                                                setRoiDraft((state) => ({
                                                    ...state,
                                                    [key]: typeof value === "number" ? value : undefined,
                                                }))
                                            }
                                        />
                                    </div>
                                ))}
                            </div>
                            <Text className="mt-2 block text-xs text-slate-500">
                                建议只框住真正的光谱条纹区域，避开边框、文字、强噪声和明显无效背景；四个值都留空时使用整张图。
                            </Text>
                        </div>

                        <Alert
                            type="info"
                            showIcon
                            message="操作建议"
                            description="第一次使用建议先点“1. 预分析偏移”，确认方向和偏移范围合理后，再点“2. 生成校正图”。如果这套参数后续要复用，可以保存为默认配置。"
                        />
                        <div className="flex flex-wrap gap-2">
                            <Button
                                icon={<Activity size={16} />}
                                loading={runningAction === "analyze"}
                                disabled={!canRunGeometry || runningAction !== null}
                                onClick={handleAnalyze}
                            >
                                1. 预分析偏移
                            </Button>
                            <Button
                                type="primary"
                                icon={<Compass size={16} />}
                                loading={runningAction === "correct"}
                                disabled={!canRunGeometry || runningAction !== null}
                                onClick={() => handleCorrect(false)}
                            >
                                2. 生成校正图
                            </Button>
                            <Button
                                icon={<Save size={16} />}
                                loading={runningAction === "correct-save-profile"}
                                disabled={!canRunGeometry || runningAction !== null}
                                onClick={() => handleCorrect(true)}
                            >
                                生成并设为默认
                            </Button>
                            <Button
                                loading={runningAction === "save-profile"}
                                disabled={runningAction !== null}
                                onClick={handleSaveProfileOnly}
                            >
                                仅保存参数为默认
                            </Button>
                        </div>
                    </div>
                </Card>
            </div>

            <Card className="border border-slate-200 shadow-sm">
                <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
                    <div>
                        <Title level={5} className="!mb-0 flex items-center gap-2 !text-slate-800">
                            <Compass size={18} />
                            几何校正结果
                        </Title>
                        <Text className="text-xs text-slate-500">
                            保存后会生成独立 RAW16 和 PNG 预览；原图、校准图、处理图都不会被覆盖。
                        </Text>
                    </div>
                    <Space wrap>
                        {metricResult && <Tag color="blue">波长方向 {metricResult.dispersionAxis}</Tag>}
                        {correctionResult?.id && <Tag color="green">已保存 #{correctionResult.id}</Tag>}
                    </Space>
                </div>

                {!metricResult ? (
                    <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="尚未分析或保存几何校正结果" />
                ) : (
                    <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_420px]">
                        <div className="flex min-h-[360px] items-center justify-center rounded-lg border border-slate-200 bg-slate-100 p-3">
                            {correctionResult?.imageDataUrl ? (
                                <button
                                    type="button"
                                    className="inline-flex cursor-zoom-in items-center justify-center rounded-md border border-slate-800 bg-slate-950 p-2 shadow-sm"
                                    onClick={() => setZoomOpen(true)}
                                >
                                    <img
                                        src={correctionResult.imageDataUrl}
                                        alt="几何校正后预览"
                                        className="block max-h-[520px] max-w-full object-contain"
                                    />
                                </button>
                            ) : (
                                <div className="flex min-h-[320px] items-center justify-center text-slate-500">
                                    只分析模式不会生成预览图，请点击“保存几何校正结果”。
                                </div>
                            )}
                        </div>

                        <div className="space-y-3">
                            <div className="grid gap-2 sm:grid-cols-2 xl:grid-cols-1">
                                <div className="rounded-lg border border-slate-200 bg-slate-50 p-3">
                                    <Text className="block text-xs text-slate-500">输入来源</Text>
                                    <span className="font-medium text-slate-800">{sourceModeText(metricResult.sourceMode)}</span>
                                </div>
                                <div className="rounded-lg border border-slate-200 bg-slate-50 p-3">
                                    <Text className="block text-xs text-slate-500">输出尺寸</Text>
                                    <span className="font-medium text-slate-800">
                                        {metricResult.width}×{metricResult.height}
                                    </span>
                                </div>
                                <div className="rounded-lg border border-slate-200 bg-slate-50 p-3">
                                    <Text className="block text-xs text-slate-500">旋转/翻转</Text>
                                    <span className="font-medium text-slate-800">
                                        {metricResult.rotateDegrees}° · X翻转{metricResult.flipX ? "是" : "否"} · Y翻转{metricResult.flipY ? "是" : "否"}
                                    </span>
                                </div>
                                <div className="rounded-lg border border-slate-200 bg-slate-50 p-3">
                                    <Text className="block text-xs text-slate-500">倾斜偏移</Text>
                                    <span className="font-medium text-slate-800">
                                        {metricResult.tiltCorrectionApplied ? "已矫正" : "未矫正"} · {metricResult.shiftMin} ~ {metricResult.shiftMax} px
                                    </span>
                                </div>
                                <div className="rounded-lg border border-slate-200 bg-slate-50 p-3">
                                    <Text className="block text-xs text-slate-500">平均绝对偏移</Text>
                                    <span className="font-medium text-slate-800">{metricResult.shiftMeanAbs.toFixed(3)} px</span>
                                </div>
                                <div className="rounded-lg border border-slate-200 bg-slate-50 p-3">
                                    <Text className="block text-xs text-slate-500">方向置信度</Text>
                                    <span className="font-medium text-slate-800">{(metricResult.axisConfidence * 100).toFixed(1)}%</span>
                                </div>
                            </div>
                            <Alert
                                type={correctionResult?.id ? "success" : "info"}
                                showIcon
                                message={metricResult.summaryMessage || "几何校正分析完成"}
                                description={`ROI：x=${metricResult.roi.xStart}~${metricResult.roi.xEnd}，y=${metricResult.roi.yStart}~${metricResult.roi.yEnd}`}
                            />
                            <Button
                                type="primary"
                                disabled={!correctionResult?.id || runningAction !== null}
                                loading={runningAction === "extract-spectrum"}
                                onClick={handleExtractSpectrum}
                            >
                                基于几何校正图提取一维光谱
                            </Button>
                        </div>
                    </div>
                )}
            </Card>

            {spectrumResult && (
                <Card className="border border-slate-200 shadow-sm">
                    <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
                        <div>
                            <Title level={5} className="!mb-0 flex items-center gap-2 !text-slate-800">
                                <Activity size={18} />
                                一维光谱结果
                            </Title>
                            <Text className="text-xs text-slate-500">
                                来源：{sourceModeText(spectrumResult.sourceMode)}
                                {spectrumResult.geometryCorrectionApplied ? " · 已使用二维几何校正结果" : ""}
                            </Text>
                        </div>
                        <Tag color={spectrumResult.geometryCorrectionApplied ? "green" : "default"}>
                            {spectrumResult.geometryCorrectionApplied ? "GEOMETRY_CORRECTED" : spectrumResult.sourceMode}
                        </Tag>
                    </div>
                    <SpectrumCurve spectrum={spectrumResult} />
                </Card>
            )}

            <Card className="border border-slate-200 shadow-sm">
                <div className="mb-4 flex items-center justify-between gap-3">
                    <div>
                        <Title level={5} className="!mb-0 !text-slate-800">历史几何校正配置</Title>
                        <Text className="text-xs text-slate-500">每个模式只建议保留一个默认启用配置，其余作为实验记录。</Text>
                    </div>
                </div>
                <Table<GeometryProfileRecord>
                    rowKey="id"
                    size="small"
                    dataSource={profiles}
                    pagination={{ pageSize: 5 }}
                    columns={[
                        {
                            title: "配置",
                            render: (_, record) => (
                                <Space wrap>
                                    <span className="font-medium">{record.profileName}</span>
                                    {record.enabled && <Tag color="green">默认</Tag>}
                                </Space>
                            ),
                        },
                        {
                            title: "来源",
                            render: (_, record) => sourceModeText(record.sourceMode),
                            width: 110,
                        },
                        {
                            title: "方向",
                            render: (_, record) => record.dispersionAxis,
                            width: 90,
                        },
                        {
                            title: "旋转",
                            render: (_, record) => `${record.rotateDegrees}°`,
                            width: 80,
                        },
                        {
                            title: "尺寸",
                            render: (_, record) =>
                                record.imageWidth && record.imageHeight ? `${record.imageWidth}×${record.imageHeight}` : "通用",
                            width: 110,
                        },
                        {
                            title: "更新时间",
                            render: (_, record) => formatDate(record.updatedAt),
                            width: 180,
                        },
                        {
                            title: "操作",
                            width: 170,
                            render: (_, record) => (
                                <Space>
                                    <Button size="small" onClick={() => applyProfileToForm(record)}>
                                        使用
                                    </Button>
                                    <Button size="small" danger onClick={() => handleDeleteProfile(record.id)}>
                                        删除
                                    </Button>
                                </Space>
                            ),
                        },
                    ]}
                />
            </Card>

            <Modal
                open={zoomOpen}
                title="几何校正后图像放大查看"
                width="92vw"
                style={{ maxWidth: 1400, top: 24 }}
                footer={<Button onClick={() => setZoomOpen(false)}>关闭</Button>}
                onCancel={() => setZoomOpen(false)}
            >
                <div className="h-[76vh] overflow-auto rounded-lg bg-slate-950 p-4 text-center">
                    {correctionResult?.imageDataUrl && (
                        <img
                            src={correctionResult.imageDataUrl}
                            alt="几何校正后图像放大"
                            className="inline-block max-w-none object-contain"
                        />
                    )}
                </div>
            </Modal>
        </div>
    );
};

export default GeometryCorrectionPage;
