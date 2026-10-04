import React, { useEffect, useMemo, useState } from "react";
import {
    Alert,
    Button,
    Card,
    Descriptions,
    Divider,
    Empty,
    InputNumber,
    Select,
    Spin,
    Switch,
    Tag,
    Typography,
} from "antd";
import { Activity, RefreshCw, Save, SlidersHorizontal } from "lucide-react";
import { toast } from "sonner";

import SpectrumCurve from "../components/SpectrumCurve";
import { jniBridgeService } from "../service/jniBridgeService";
import { useJNIStore } from "../store/jniStore";
import type {
    ImageFrameRecord,
    SpectrumExtractionRecord,
    SpectrumPoint,
    SpectrumPreprocessingRecord,
    SpectrumPreprocessingRequest,
    SpectrumRoi,
} from "../types/jni";

const { Title, Text } = Typography;

type RequiredPreprocessingConfig = Required<SpectrumPreprocessingRequest>;

const DEFAULT_CONFIG: RequiredPreprocessingConfig = {
    spikeCorrectionEnabled: true,
    spikeWindowRadius: 2,
    spikeThresholdMad: 8,
    smoothingEnabled: true,
    smoothingMethod: "SAVITZKY_GOLAY",
    smoothingWindow: 7,
    smoothingPolynomialOrder: 2,
    baselineCorrectionEnabled: false,
    baselineMethod: "ROLLING_PERCENTILE",
    baselineWindow: 51,
    baselinePercentile: 10,
    normalizationEnabled: false,
    normalizationMethod: "MAX",
};

const EMPTY_ROI: SpectrumRoi = {
    xStart: 0,
    xEnd: 0,
    yStart: 0,
    yEnd: 0,
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

const formatNumber = (value?: number | null, digits = 3): string => {
    if (typeof value !== "number" || !Number.isFinite(value)) {
        return "-";
    }
    if (Math.abs(value) >= 1000) {
        return value.toFixed(1);
    }
    return value.toFixed(digits);
};

const formatTime = (value?: string | null): string => {
    if (!value) {
        return "-";
    }
    const time = new Date(value);
    return Number.isNaN(time.getTime()) ? value : time.toLocaleString();
};

const statsFromPoints = (points: SpectrumPoint[]) => {
    if (points.length === 0) {
        return { min: 0, max: 0, mean: 0 };
    }
    let min = points[0].intensity;
    let max = points[0].intensity;
    let sum = 0;
    points.forEach((point) => {
        min = Math.min(min, point.intensity);
        max = Math.max(max, point.intensity);
        sum += point.intensity;
    });
    return { min, max, mean: sum / points.length };
};

const buildCurveRecord = (
    points: SpectrumPoint[],
    seed: Partial<SpectrumExtractionRecord>,
    id: number,
    summaryMessage: string,
): SpectrumExtractionRecord => {
    const stats = statsFromPoints(points);
    return {
        id,
        imageId: seed.imageId ?? 0,
        captureId: seed.captureId ?? 0,
        sourceMode: seed.sourceMode ?? "SPECTRUM",
        sourceQualityStatus: seed.sourceQualityStatus ?? "PASS",
        geometryCorrectionApplied: seed.geometryCorrectionApplied ?? false,
        geometryCorrectionId: seed.geometryCorrectionId ?? null,
        geometryProfileId: seed.geometryProfileId ?? null,
        geometrySummaryMessage: seed.geometrySummaryMessage ?? null,
        wavelengthAxis: seed.wavelengthAxis ?? "X",
        roi: seed.roi ?? EMPTY_ROI,
        rectified: seed.rectified ?? false,
        maxShiftPixels: seed.maxShiftPixels ?? 0,
        shiftMin: seed.shiftMin ?? 0,
        shiftMax: seed.shiftMax ?? 0,
        shiftMeanAbs: seed.shiftMeanAbs ?? 0,
        integrationMethod: seed.integrationMethod ?? "MEAN",
        pointCount: points.length,
        intensityMin: stats.min,
        intensityMax: stats.max,
        intensityMean: stats.mean,
        points,
        algorithmVersion: seed.algorithmVersion ?? "",
        summaryMessage,
        details: seed.details ?? null,
        createdAt: seed.createdAt ?? new Date().toISOString(),
    };
};

const getImageLabel = (frame: ImageFrameRecord): string =>
    `#${frame.id} · ${frame.width}×${frame.height} · ${formatTime(frame.timestamp)}`;

const stepEnabled = (record: SpectrumPreprocessingRecord | null, key: string): boolean | null => {
    const value = record?.preprocessingSteps?.[key];
    return typeof value === "boolean" ? value : null;
};

const SpectrumPreprocessingPage: React.FC = () => {
    const { workMode } = useJNIStore();
    const [config, setConfig] = useState<RequiredPreprocessingConfig>(DEFAULT_CONFIG);
    const [frames, setFrames] = useState<ImageFrameRecord[]>([]);
    const [selectedImageId, setSelectedImageId] = useState<number | null>(null);
    const [sourceSpectrum, setSourceSpectrum] = useState<SpectrumExtractionRecord | null>(null);
    const [preprocessedSpectrum, setPreprocessedSpectrum] = useState<SpectrumPreprocessingRecord | null>(null);
    const [loadingFrames, setLoadingFrames] = useState(false);
    const [loadingSpectrum, setLoadingSpectrum] = useState(false);
    const [preprocessing, setPreprocessing] = useState(false);

    const selectedFrame = useMemo(
        () => frames.find((frame) => frame.id === selectedImageId) ?? null,
        [frames, selectedImageId],
    );

    const originalCurve = useMemo(() => {
        const points = sourceSpectrum?.points ?? preprocessedSpectrum?.originalPoints ?? [];
        if (points.length === 0) {
            return null;
        }
        return buildCurveRecord(
            points,
            sourceSpectrum ?? {
                imageId: preprocessedSpectrum?.imageId,
                captureId: preprocessedSpectrum?.captureId,
            },
            sourceSpectrum?.id ?? preprocessedSpectrum?.spectrumId ?? 0,
            sourceSpectrum?.summaryMessage ?? "预处理前一维光谱",
        );
    }, [preprocessedSpectrum, sourceSpectrum]);

    const processedCurve = useMemo(() => {
        if (!preprocessedSpectrum || preprocessedSpectrum.points.length === 0) {
            return null;
        }
        return buildCurveRecord(
            preprocessedSpectrum.points,
            sourceSpectrum ?? {
                imageId: preprocessedSpectrum.imageId,
                captureId: preprocessedSpectrum.captureId,
            },
            preprocessedSpectrum.id + 100000000,
            preprocessedSpectrum.summaryMessage,
        );
    }, [preprocessedSpectrum, sourceSpectrum]);

    const loadFrames = async () => {
        setLoadingFrames(true);
        try {
            const nextFrames = workMode === "HDR"
                ? await jniBridgeService.loadHdrImageHistory()
                : await jniBridgeService.loadImageHistory();
            setFrames(nextFrames);
            setSelectedImageId((current) => {
                if (current && nextFrames.some((frame) => frame.id === current)) {
                    return current;
                }
                return nextFrames[0]?.id ?? null;
            });
        } catch (error) {
            toast.error(error instanceof Error ? error.message : "加载图像历史失败");
        } finally {
            setLoadingFrames(false);
        }
    };

    const loadSpectrum = async (imageId: number | null) => {
        if (!imageId) {
            setSourceSpectrum(null);
            setPreprocessedSpectrum(null);
            return;
        }
        setLoadingSpectrum(true);
        setSourceSpectrum(null);
        setPreprocessedSpectrum(null);
        try {
            const [latestSpectrum, latestPreprocessing] = await Promise.all([
                jniBridgeService.getLatestSpectrum(imageId),
                jniBridgeService.getLatestSpectrumPreprocessing(imageId),
            ]);
            setSourceSpectrum(latestSpectrum);
            setPreprocessedSpectrum(latestPreprocessing);
        } catch (error) {
            toast.error(error instanceof Error ? error.message : "读取光谱记录失败");
        } finally {
            setLoadingSpectrum(false);
        }
    };

    useEffect(() => {
        loadFrames();
    }, [workMode]);

    useEffect(() => {
        loadSpectrum(selectedImageId);
    }, [selectedImageId]);

    const updateConfig = <K extends keyof RequiredPreprocessingConfig>(
        key: K,
        value: RequiredPreprocessingConfig[K],
    ) => {
        setConfig((current) => ({
            ...current,
            [key]: value,
        }));
    };

    const handlePreprocess = async () => {
        if (!selectedImageId) {
            toast.error("请先选择一张图像");
            return;
        }
        if (!sourceSpectrum) {
            toast.error("当前图像还没有一维光谱，请先在采集页或图像管理页提取一维光谱");
            return;
        }
        setPreprocessing(true);
        try {
            const result = await jniBridgeService.preprocessSpectrum(selectedImageId, config);
            setPreprocessedSpectrum(result);
            toast.success("一维光谱预处理完成，结果已覆盖保存");
        } catch (error) {
            toast.error(error instanceof Error ? error.message : "一维光谱预处理失败");
        } finally {
            setPreprocessing(false);
        }
    };

    return (
        <div className="space-y-6">
            <div className="flex flex-wrap items-start justify-between gap-4">
                <div>
                    <Title level={3} className="!mb-1 flex items-center gap-2">
                        <SlidersHorizontal size={24} />
                        光谱预处理
                    </Title>
                    <Text type="secondary">
                        对已经提取的一维 pixelIndex-intensity 曲线进行尖刺修正、平滑、背景扣除和归一化。
                    </Text>
                </div>
                <Tag color={workMode === "HDR" ? "purple" : "blue"} className="mt-1">
                    当前模式：{workMode === "HDR" ? "HDR" : "普通"}
                </Tag>
            </div>

            <Card>
                <div className="grid gap-4 lg:grid-cols-[minmax(0,1.2fr)_minmax(360px,0.8fr)]">
                    <div className="space-y-4">
                        <div className="flex flex-wrap items-end gap-3">
                            <div className="min-w-[320px] flex-1">
                                <Text className="mb-1 block text-xs text-slate-500">选择图像</Text>
                                <Select
                                    className="w-full"
                                    placeholder="请选择已经提取过一维光谱的图像"
                                    loading={loadingFrames}
                                    value={selectedImageId ?? undefined}
                                    onChange={(value) => setSelectedImageId(value)}
                                    options={frames.map((frame) => ({
                                        value: frame.id,
                                        label: getImageLabel(frame),
                                    }))}
                                />
                            </div>
                            <Button icon={<RefreshCw size={16} />} loading={loadingFrames} onClick={loadFrames}>
                                刷新历史
                            </Button>
                            <Button
                                type="primary"
                                icon={<Save size={16} />}
                                loading={preprocessing}
                                disabled={!sourceSpectrum}
                                onClick={handlePreprocess}
                            >
                                执行预处理并保存
                            </Button>
                        </div>

                        {selectedFrame ? (
                            <div className="grid gap-4 md:grid-cols-[260px_minmax(0,1fr)]">
                                <div className="overflow-hidden rounded-xl border border-slate-200 bg-slate-950">
                                    {selectedFrame.imageDataUrl ? (
                                        <img
                                            src={selectedFrame.imageDataUrl}
                                            alt="selected spectral frame"
                                            className="h-44 w-full object-contain"
                                        />
                                    ) : (
                                        <div className="flex h-44 items-center justify-center text-slate-500">
                                            暂无预览图
                                        </div>
                                    )}
                                </div>
                                <Descriptions size="small" column={2} bordered>
                                    <Descriptions.Item label="图像编号">#{selectedFrame.id}</Descriptions.Item>
                                    <Descriptions.Item label="图像质量">
                                        <Tag color={qualityColor(selectedFrame.qualityStatus)}>
                                            {selectedFrame.qualityStatus ?? "UNKNOWN"}
                                        </Tag>
                                    </Descriptions.Item>
                                    <Descriptions.Item label="尺寸">
                                        {selectedFrame.width}×{selectedFrame.height}
                                    </Descriptions.Item>
                                    <Descriptions.Item label="采集类型">
                                        {selectedFrame.captureScene}
                                    </Descriptions.Item>
                                    <Descriptions.Item label="一维光谱">
                                        {sourceSpectrum ? (
                                            <Tag color="green">已提取 #{sourceSpectrum.id}</Tag>
                                        ) : (
                                            <Tag color="red">未提取</Tag>
                                        )}
                                    </Descriptions.Item>
                                    <Descriptions.Item label="预处理">
                                        {preprocessedSpectrum ? (
                                            <Tag color="cyan">已保存 #{preprocessedSpectrum.id}</Tag>
                                        ) : (
                                            <Tag>暂无</Tag>
                                        )}
                                    </Descriptions.Item>
                                </Descriptions>
                            </div>
                        ) : (
                            <Empty description="暂无图像历史" />
                        )}

                        {!loadingSpectrum && selectedFrame && !sourceSpectrum && (
                            <Alert
                                type="warning"
                                showIcon
                                message="当前图像还不能做光谱预处理"
                                description="预处理模块只处理已经提取出来的一维光谱曲线。请先在普通/HDR采集页或图像管理页完成一维光谱提取。"
                            />
                        )}
                    </div>

                    <div className="rounded-xl border border-slate-200 bg-slate-50 p-4">
                        <div className="mb-3 flex items-center gap-2">
                            <Activity size={18} className="text-blue-500" />
                            <div className="font-semibold text-slate-800">预处理参数</div>
                        </div>
                        <div className="space-y-4">
                            <div className="rounded-lg bg-white p-3 shadow-sm">
                                <div className="mb-2 flex items-center justify-between">
                                    <Text strong>孤立尖刺修正</Text>
                                    <Switch
                                        checked={config.spikeCorrectionEnabled}
                                        onChange={(value) => updateConfig("spikeCorrectionEnabled", value)}
                                    />
                                </div>
                                <div className="grid grid-cols-2 gap-3">
                                    <div>
                                        <Text className="mb-1 block text-xs text-slate-500">邻域半径</Text>
                                        <InputNumber
                                            min={1}
                                            max={10}
                                            value={config.spikeWindowRadius}
                                            className="w-full"
                                            disabled={!config.spikeCorrectionEnabled}
                                            onChange={(value) => updateConfig("spikeWindowRadius", Number(value ?? 2))}
                                        />
                                    </div>
                                    <div>
                                        <Text className="mb-1 block text-xs text-slate-500">MAD阈值倍数</Text>
                                        <InputNumber
                                            min={3}
                                            max={30}
                                            step={0.5}
                                            value={config.spikeThresholdMad}
                                            className="w-full"
                                            disabled={!config.spikeCorrectionEnabled}
                                            onChange={(value) => updateConfig("spikeThresholdMad", Number(value ?? 8))}
                                        />
                                    </div>
                                </div>
                            </div>

                            <div className="rounded-lg bg-white p-3 shadow-sm">
                                <div className="mb-2 flex items-center justify-between">
                                    <Text strong>曲线平滑</Text>
                                    <Switch
                                        checked={config.smoothingEnabled}
                                        onChange={(value) => updateConfig("smoothingEnabled", value)}
                                    />
                                </div>
                                <div className="grid grid-cols-3 gap-3">
                                    <div className="col-span-3">
                                        <Text className="mb-1 block text-xs text-slate-500">方法</Text>
                                        <Select
                                            className="w-full"
                                            value={config.smoothingMethod}
                                            disabled={!config.smoothingEnabled}
                                            onChange={(value) => updateConfig("smoothingMethod", value)}
                                            options={[
                                                { value: "SAVITZKY_GOLAY", label: "Savitzky-Golay" },
                                                { value: "MOVING_AVERAGE", label: "移动平均" },
                                            ]}
                                        />
                                    </div>
                                    <div>
                                        <Text className="mb-1 block text-xs text-slate-500">窗口</Text>
                                        <InputNumber
                                            min={3}
                                            max={51}
                                            value={config.smoothingWindow}
                                            className="w-full"
                                            disabled={!config.smoothingEnabled}
                                            onChange={(value) => updateConfig("smoothingWindow", Number(value ?? 7))}
                                        />
                                    </div>
                                    <div>
                                        <Text className="mb-1 block text-xs text-slate-500">阶数</Text>
                                        <InputNumber
                                            min={1}
                                            max={5}
                                            value={config.smoothingPolynomialOrder}
                                            className="w-full"
                                            disabled={!config.smoothingEnabled || config.smoothingMethod !== "SAVITZKY_GOLAY"}
                                            onChange={(value) =>
                                                updateConfig("smoothingPolynomialOrder", Number(value ?? 2))
                                            }
                                        />
                                    </div>
                                </div>
                            </div>

                            <div className="rounded-lg bg-white p-3 shadow-sm">
                                <div className="mb-2 flex items-center justify-between">
                                    <Text strong>背景/基线扣除</Text>
                                    <Switch
                                        checked={config.baselineCorrectionEnabled}
                                        onChange={(value) => updateConfig("baselineCorrectionEnabled", value)}
                                    />
                                </div>
                                <div className="grid grid-cols-2 gap-3">
                                    <div>
                                        <Text className="mb-1 block text-xs text-slate-500">窗口</Text>
                                        <InputNumber
                                            min={5}
                                            max={401}
                                            value={config.baselineWindow}
                                            className="w-full"
                                            disabled={!config.baselineCorrectionEnabled}
                                            onChange={(value) => updateConfig("baselineWindow", Number(value ?? 51))}
                                        />
                                    </div>
                                    <div>
                                        <Text className="mb-1 block text-xs text-slate-500">低分位数%</Text>
                                        <InputNumber
                                            min={1}
                                            max={45}
                                            step={1}
                                            value={config.baselinePercentile}
                                            className="w-full"
                                            disabled={!config.baselineCorrectionEnabled}
                                            onChange={(value) => updateConfig("baselinePercentile", Number(value ?? 10))}
                                        />
                                    </div>
                                </div>
                            </div>

                            <div className="rounded-lg bg-white p-3 shadow-sm">
                                <div className="mb-2 flex items-center justify-between">
                                    <Text strong>强度归一化</Text>
                                    <Switch
                                        checked={config.normalizationEnabled}
                                        onChange={(value) => updateConfig("normalizationEnabled", value)}
                                    />
                                </div>
                                <Select
                                    className="w-full"
                                    value={config.normalizationMethod}
                                    disabled={!config.normalizationEnabled}
                                    onChange={(value) => updateConfig("normalizationMethod", value)}
                                    options={[
                                        { value: "MAX", label: "最大值归一化" },
                                        { value: "AREA", label: "面积归一化" },
                                        { value: "MIN_MAX", label: "Min-Max归一化" },
                                    ]}
                                />
                            </div>
                        </div>
                    </div>
                </div>
            </Card>

            <Spin spinning={loadingSpectrum}>
                <div className="grid gap-4 xl:grid-cols-2">
                    <Card title="预处理前一维光谱">
                        {originalCurve ? (
                            <SpectrumCurve spectrum={originalCurve} />
                        ) : (
                            <Empty description="暂无可展示的一维光谱" />
                        )}
                    </Card>
                    <Card title="预处理后一维光谱">
                        {processedCurve ? (
                            <SpectrumCurve spectrum={processedCurve} />
                        ) : (
                            <Empty description="尚未执行预处理" />
                        )}
                    </Card>
                </div>
            </Spin>

            <Card title="预处理结果说明">
                {preprocessedSpectrum ? (
                    <div className="space-y-4">
                        <Alert
                            type="success"
                            showIcon
                            message={preprocessedSpectrum.summaryMessage}
                            description="预处理结果已按光谱记录覆盖保存；后续如果修改参数重新执行，会替换当前结果。"
                        />
                        <div className="flex flex-wrap gap-2">
                            <Tag color={stepEnabled(preprocessedSpectrum, "spikeCorrectionEnabled") ? "green" : "default"}>
                                尖刺修正 {stepEnabled(preprocessedSpectrum, "spikeCorrectionEnabled") ? "启用" : "关闭"}
                            </Tag>
                            <Tag color={stepEnabled(preprocessedSpectrum, "smoothingEnabled") ? "blue" : "default"}>
                                平滑 {String(preprocessedSpectrum.preprocessingSteps?.smoothingMethod ?? "关闭")}
                            </Tag>
                            <Tag color={stepEnabled(preprocessedSpectrum, "baselineCorrectionEnabled") ? "orange" : "default"}>
                                背景扣除 {stepEnabled(preprocessedSpectrum, "baselineCorrectionEnabled") ? "启用" : "关闭"}
                            </Tag>
                            <Tag color={stepEnabled(preprocessedSpectrum, "normalizationEnabled") ? "purple" : "default"}>
                                归一化 {String(preprocessedSpectrum.preprocessingSteps?.normalizationMethod ?? "NONE")}
                            </Tag>
                        </div>
                        <Descriptions bordered size="small" column={{ xs: 1, md: 2, xl: 3 }}>
                            <Descriptions.Item label="光谱点数">{preprocessedSpectrum.pointCount}</Descriptions.Item>
                            <Descriptions.Item label="修正尖刺">{preprocessedSpectrum.spikeCount} 个</Descriptions.Item>
                            <Descriptions.Item label="保存时间">{formatTime(preprocessedSpectrum.createdAt)}</Descriptions.Item>
                            <Descriptions.Item label="噪声估计">
                                {formatNumber(preprocessedSpectrum.noiseBefore)} →{" "}
                                {formatNumber(preprocessedSpectrum.noiseAfter)}
                            </Descriptions.Item>
                            <Descriptions.Item label="动态范围">
                                {formatNumber(preprocessedSpectrum.dynamicRangeBefore)} →{" "}
                                {formatNumber(preprocessedSpectrum.dynamicRangeAfter)}
                            </Descriptions.Item>
                            <Descriptions.Item label="强度均值">
                                {formatNumber(preprocessedSpectrum.originalIntensityMean)} →{" "}
                                {formatNumber(preprocessedSpectrum.processedIntensityMean)}
                            </Descriptions.Item>
                        </Descriptions>
                        <Divider className="my-3" />
                        <Text type="secondary">
                            当前实现属于像素域光谱预处理，还没有做波长 nm 标定；横坐标仍然是 pixelIndex。
                            如果开启归一化，纵坐标会变成相对强度，不再是原始 DN 派生强度。
                        </Text>
                    </div>
                ) : (
                    <Empty description="当前图片暂无预处理结果" />
                )}
            </Card>
        </div>
    );
};

export default SpectrumPreprocessingPage;
