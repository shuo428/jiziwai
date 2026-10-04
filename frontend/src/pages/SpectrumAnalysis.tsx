import React, { useEffect, useMemo, useState } from "react";
import {
    Alert,
    Button,
    Card,
    Descriptions,
    Empty,
    InputNumber,
    Select,
    Spin,
    Table,
    Tag,
    Typography,
} from "antd";
import type { ColumnsType } from "antd/es/table";
import { ChartNoAxesCombined, Info, RefreshCw, Save } from "lucide-react";
import { toast } from "sonner";

import SpectrumCurve from "../components/SpectrumCurve";
import type { SpectrumMarker } from "../components/SpectrumCurve";
import { jniBridgeService } from "../service/jniBridgeService";
import { useJNIStore } from "../store/jniStore";
import type {
    ImageFrameRecord,
    SpectrumAnalysisRecord,
    SpectrumAnalysisRequest,
    SpectrumExtractionRecord,
    SpectrumPeakFitRecord,
    SpectrumPoint,
    SpectrumPreprocessingRecord,
    SpectrumRoi,
} from "../types/jni";

const { Title, Text } = Typography;

type RequiredAnalysisConfig = Required<SpectrumAnalysisRequest>;

const DEFAULT_CONFIG: RequiredAnalysisConfig = {
    source: "AUTO",
    minProminenceRatio: 0.04,
    minSignalToNoise: 4,
    minDistancePixels: 8,
    fitWindowRadius: 12,
    maxPeaks: 20,
};

const EMPTY_ROI: SpectrumRoi = { xStart: 0, xEnd: 0, yStart: 0, yEnd: 0 };

const formatNumber = (value?: number | null, digits = 3): string => {
    if (typeof value !== "number" || !Number.isFinite(value)) {
        return "-";
    }
    if (Math.abs(value) >= 10000) {
        return value.toFixed(0);
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

/** 将预处理点列包装为现有曲线组件可直接展示的记录，不会写回数据库。 */
const buildCurveRecord = (
    points: SpectrumPoint[],
    seed: SpectrumExtractionRecord | null,
    id: number,
    summaryMessage: string,
): SpectrumExtractionRecord => {
    const stats = statsFromPoints(points);
    return {
        id,
        imageId: seed?.imageId ?? 0,
        captureId: seed?.captureId ?? 0,
        sourceMode: seed?.sourceMode ?? "SPECTRUM",
        sourceQualityStatus: seed?.sourceQualityStatus ?? "PASS",
        geometryCorrectionApplied: seed?.geometryCorrectionApplied ?? false,
        geometryCorrectionId: seed?.geometryCorrectionId ?? null,
        geometryProfileId: seed?.geometryProfileId ?? null,
        geometrySummaryMessage: seed?.geometrySummaryMessage ?? null,
        wavelengthAxis: seed?.wavelengthAxis ?? "X",
        roi: seed?.roi ?? EMPTY_ROI,
        rectified: seed?.rectified ?? false,
        maxShiftPixels: seed?.maxShiftPixels ?? 0,
        shiftMin: seed?.shiftMin ?? 0,
        shiftMax: seed?.shiftMax ?? 0,
        shiftMeanAbs: seed?.shiftMeanAbs ?? 0,
        integrationMethod: seed?.integrationMethod ?? "MEAN",
        pointCount: points.length,
        intensityMin: stats.min,
        intensityMax: stats.max,
        intensityMean: stats.mean,
        points,
        algorithmVersion: seed?.algorithmVersion ?? "",
        summaryMessage,
        details: seed?.details ?? null,
        createdAt: seed?.createdAt ?? new Date().toISOString(),
    };
};

const imageLabel = (frame: ImageFrameRecord): string =>
    `#${frame.id} · ${frame.width}×${frame.height} · ${formatTime(frame.timestamp)}`;

const sourceText = (source?: string | null): string =>
    source === "PREPROCESSED" ? "预处理一维光谱" : "原始一维光谱";

const SpectrumAnalysisPage: React.FC = () => {
    const { workMode } = useJNIStore();
    const [config, setConfig] = useState<RequiredAnalysisConfig>(DEFAULT_CONFIG);
    const [frames, setFrames] = useState<ImageFrameRecord[]>([]);
    const [selectedImageId, setSelectedImageId] = useState<number | null>(null);
    const [extractedSpectrum, setExtractedSpectrum] = useState<SpectrumExtractionRecord | null>(null);
    const [preprocessing, setPreprocessing] = useState<SpectrumPreprocessingRecord | null>(null);
    const [analysis, setAnalysis] = useState<SpectrumAnalysisRecord | null>(null);
    const [loadingFrames, setLoadingFrames] = useState(false);
    const [loadingData, setLoadingData] = useState(false);
    const [analyzing, setAnalyzing] = useState(false);

    const selectedFrame = useMemo(
        () => frames.find((item) => item.id === selectedImageId) ?? null,
        [frames, selectedImageId],
    );

    const displayedPoints = useMemo(() => {
        const shouldUsePreprocessed = (config.source === "PREPROCESSED" || config.source === "AUTO")
            && Boolean(preprocessing?.points?.length);
        return shouldUsePreprocessed ? preprocessing?.points ?? [] : extractedSpectrum?.points ?? [];
    }, [config.source, extractedSpectrum, preprocessing]);

    const displayedSource = useMemo(() => {
        if (config.source === "PREPROCESSED" || (config.source === "AUTO" && preprocessing?.points?.length)) {
            return "PREPROCESSED";
        }
        return "EXTRACTED";
    }, [config.source, preprocessing]);

    const curve = useMemo(() => {
        if (!displayedPoints.length) {
            return null;
        }
        return buildCurveRecord(
            displayedPoints,
            extractedSpectrum,
            (analysis?.id ?? extractedSpectrum?.id ?? 0) + (displayedSource === "PREPROCESSED" ? 100000000 : 0),
            `${sourceText(displayedSource)} · 像素坐标域`,
        );
    }, [analysis?.id, displayedPoints, displayedSource, extractedSpectrum]);

    const peakMarkers = useMemo<SpectrumMarker[]>(() => {
        // 用户切换输入曲线但尚未重新分析时，旧峰位不应标到新曲线上，以免造成结果来源歧义。
        if (!analysis || analysis.source !== displayedSource) {
            return [];
        }
        return analysis.peaks.map((peak) => ({
            pixelIndex: peak.fittedCenterPixel,
            label: `峰 ${peak.rank}`,
            color: "#fbbf24",
        }));
    }, [analysis, displayedSource]);

    const loadFrames = async () => {
        setLoadingFrames(true);
        try {
            const nextFrames = workMode === "HDR"
                ? await jniBridgeService.loadHdrImageHistory()
                : await jniBridgeService.loadImageHistory();
            setFrames(nextFrames);
            setSelectedImageId((current) => {
                if (current && nextFrames.some((item) => item.id === current)) {
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

    const loadAnalysisData = async (imageId: number | null) => {
        if (!imageId) {
            setExtractedSpectrum(null);
            setPreprocessing(null);
            setAnalysis(null);
            return;
        }
        setLoadingData(true);
        setExtractedSpectrum(null);
        setPreprocessing(null);
        setAnalysis(null);
        try {
            const [spectrum, latestPreprocessing, latestAnalysis] = await Promise.all([
                jniBridgeService.getLatestSpectrum(imageId),
                jniBridgeService.getLatestSpectrumPreprocessing(imageId),
                jniBridgeService.getLatestSpectrumAnalysis(imageId),
            ]);
            setExtractedSpectrum(spectrum);
            setPreprocessing(latestPreprocessing);
            setAnalysis(latestAnalysis);
            if (latestAnalysis) {
                setConfig((current) => ({
                    ...current,
                    source: latestAnalysis.source === "PREPROCESSED" ? "PREPROCESSED" : "EXTRACTED",
                    minProminenceRatio: Number(latestAnalysis.analysisParameters?.minProminenceRatio ?? current.minProminenceRatio),
                    minSignalToNoise: Number(latestAnalysis.analysisParameters?.minSignalToNoise ?? current.minSignalToNoise),
                    minDistancePixels: Number(latestAnalysis.analysisParameters?.minDistancePixels ?? current.minDistancePixels),
                    fitWindowRadius: Number(latestAnalysis.analysisParameters?.fitWindowRadius ?? current.fitWindowRadius),
                    maxPeaks: Number(latestAnalysis.analysisParameters?.maxPeaks ?? current.maxPeaks),
                }));
            }
        } catch (error) {
            toast.error(error instanceof Error ? error.message : "读取光谱分析记录失败");
        } finally {
            setLoadingData(false);
        }
    };

    useEffect(() => {
        loadFrames();
    }, [workMode]);

    useEffect(() => {
        loadAnalysisData(selectedImageId);
    }, [selectedImageId]);

    const updateConfig = <K extends keyof RequiredAnalysisConfig>(key: K, value: RequiredAnalysisConfig[K]) => {
        setConfig((current) => ({ ...current, [key]: value }));
    };

    const handleAnalyze = async () => {
        if (!selectedImageId) {
            toast.error("请先选择一张图像");
            return;
        }
        if (!extractedSpectrum) {
            toast.error("当前图像还没有一维光谱，请先完成一维光谱提取");
            return;
        }
        if (config.source === "PREPROCESSED" && !preprocessing) {
            toast.error("当前图像没有预处理结果，不能选择预处理一维光谱");
            return;
        }
        setAnalyzing(true);
        try {
            const result = await jniBridgeService.analyzeSpectrum(selectedImageId, config);
            setAnalysis(result);
            toast.success("谱峰检测与高斯拟合已完成；同一输入来源的旧结果已更新，另一来源结果会保留用于对比");
        } catch (error) {
            toast.error(error instanceof Error ? error.message : "光谱拟合与分析失败");
        } finally {
            setAnalyzing(false);
        }
    };

    const columns: ColumnsType<SpectrumPeakFitRecord> = [
        {
            title: "#",
            dataIndex: "rank",
            width: 54,
            fixed: "left",
        },
        {
            title: "峰中心（pixelIndex）",
            dataIndex: "fittedCenterPixel",
            width: 146,
            render: (value: number, record) => (
                <div>
                    <div className="font-medium text-slate-800">{formatNumber(value, 3)}</div>
                    <Text className="text-xs text-slate-500">观测点 {record.observedPixelIndex}</Text>
                </div>
            ),
        },
        {
            title: "FWHM（像素）",
            dataIndex: "fwhmPixels",
            width: 122,
            render: (value: number) => formatNumber(value, 3),
        },
        {
            title: "振幅",
            dataIndex: "amplitude",
            width: 112,
            render: (value: number) => formatNumber(value, 2),
        },
        {
            title: "峰面积",
            dataIndex: "area",
            width: 112,
            render: (value: number) => formatNumber(value, 2),
        },
        {
            title: "突出度",
            dataIndex: "prominence",
            width: 108,
            render: (value: number) => formatNumber(value, 2),
        },
        {
            title: "SNR",
            dataIndex: "signalToNoise",
            width: 92,
            render: (value: number) => formatNumber(value, 2),
        },
        {
            title: "R²",
            dataIndex: "rSquared",
            width: 88,
            render: (value: number) => formatNumber(value, 4),
        },
        {
            title: "拟合质量",
            dataIndex: "fitQuality",
            width: 122,
            fixed: "right",
            render: (value: string) => (
                <Tag color={value === "GOOD" ? "green" : "orange"}>
                    {value === "GOOD" ? "可信" : "低置信度"}
                </Tag>
            ),
        },
    ];

    return (
        <div className="space-y-6">
            <div className="flex flex-wrap items-start justify-between gap-4">
                <div>
                    <Title level={3} className="!mb-1 flex items-center gap-2">
                        <ChartNoAxesCombined size={24} />
                        光谱拟合与分析
                    </Title>
                    <Text type="secondary">
                        在一维光谱上检测正向峰，并在局部窗口中拟合“高斯峰 + 线性基线”。
                    </Text>
                </div>
                <Tag color={workMode === "HDR" ? "purple" : "blue"} className="mt-1">
                    当前模式：{workMode === "HDR" ? "HDR" : "普通"}
                </Tag>
            </div>

            <Alert
                showIcon
                type="info"
                icon={<Info size={18} />}
                message="当前为像素坐标域分析，不是波长标定结果"
                description="峰中心、半高宽和峰面积的横向尺度均为 pixelIndex。没有可靠标定光源或光学标定参数时，系统不会把这些位置标成 nm，也不会据此给出元素识别结论。"
            />

            <Card>
                <div className="grid gap-5 xl:grid-cols-[minmax(0,1.18fr)_minmax(360px,0.82fr)]">
                    <div className="space-y-4">
                        <div className="flex flex-wrap items-end gap-3">
                            <div className="min-w-[300px] flex-1">
                                <Text className="mb-1 block text-xs text-slate-500">选择已提取一维光谱的图像</Text>
                                <Select
                                    className="w-full"
                                    loading={loadingFrames}
                                    value={selectedImageId ?? undefined}
                                    placeholder="请选择图像"
                                    onChange={(value) => setSelectedImageId(value)}
                                    options={frames.map((frame) => ({ value: frame.id, label: imageLabel(frame) }))}
                                />
                            </div>
                            <Button icon={<RefreshCw size={16} />} loading={loadingFrames} onClick={loadFrames}>
                                刷新历史
                            </Button>
                            <Button
                                type="primary"
                                icon={<Save size={16} />}
                                loading={analyzing}
                                disabled={!extractedSpectrum}
                                onClick={handleAnalyze}
                            >
                                执行拟合并保存
                            </Button>
                        </div>

                        {selectedFrame ? (
                            <Descriptions bordered size="small" column={{ xs: 1, md: 2 }}>
                                <Descriptions.Item label="图像">#{selectedFrame.id} · {selectedFrame.width}×{selectedFrame.height}</Descriptions.Item>
                                <Descriptions.Item label="一维光谱">
                                    {extractedSpectrum ? <Tag color="green">已提取 #{extractedSpectrum.id}</Tag> : <Tag color="red">未提取</Tag>}
                                </Descriptions.Item>
                                <Descriptions.Item label="预处理曲线">
                                    {preprocessing ? <Tag color="cyan">已保存 #{preprocessing.id}</Tag> : <Tag>暂无</Tag>}
                                </Descriptions.Item>
                                <Descriptions.Item label="当前分析">
                                    {analysis ? <Tag color="blue">已保存 #{analysis.id}</Tag> : <Tag>暂无</Tag>}
                                </Descriptions.Item>
                            </Descriptions>
                        ) : <Empty description="暂无图像历史" />}

                        {!loadingData && selectedFrame && !extractedSpectrum && (
                            <Alert
                                type="warning"
                                showIcon
                                message="当前图片还没有一维光谱"
                                description="请先在采集页、图像管理页或几何校正页提取一维光谱，再到这里执行峰检测和拟合。"
                            />
                        )}
                    </div>

                    <div className="rounded-xl border border-slate-200 bg-slate-50 p-4">
                        <div className="mb-3 font-semibold text-slate-800">谱峰检测与拟合参数</div>
                        <div className="grid gap-3 sm:grid-cols-2">
                            <div className="sm:col-span-2">
                                <Text className="mb-1 block text-xs text-slate-500">分析输入</Text>
                                <Select
                                    className="w-full"
                                    value={config.source}
                                    onChange={(value) => updateConfig("source", value)}
                                    options={[
                                        { value: "AUTO", label: "自动：优先使用预处理曲线" },
                                        { value: "PREPROCESSED", label: "仅使用预处理曲线" },
                                        { value: "EXTRACTED", label: "仅使用原始一维曲线" },
                                    ]}
                                />
                            </div>
                            <div className="sm:col-span-2">
                                <Text className="mb-1 block text-xs text-slate-500">最多保留峰数</Text>
                                <InputNumber min={1} max={200} className="w-full" value={config.maxPeaks}
                                    onChange={(value) => updateConfig("maxPeaks", Number(value ?? 20))} />
                            </div>
                            <div>
                                <Text className="mb-1 block text-xs text-slate-500">最小突出度比例</Text>
                                <InputNumber min={0.001} max={0.8} step={0.005} className="w-full" value={config.minProminenceRatio}
                                    onChange={(value) => updateConfig("minProminenceRatio", Number(value ?? 0.04))} />
                            </div>
                            <div>
                                <Text className="mb-1 block text-xs text-slate-500">最低 SNR</Text>
                                <InputNumber min={1} max={100} step={0.5} className="w-full" value={config.minSignalToNoise}
                                    onChange={(value) => updateConfig("minSignalToNoise", Number(value ?? 4))} />
                            </div>
                            <div>
                                <Text className="mb-1 block text-xs text-slate-500">峰间最小距离（px）</Text>
                                <InputNumber min={1} max={500} className="w-full" value={config.minDistancePixels}
                                    onChange={(value) => updateConfig("minDistancePixels", Number(value ?? 8))} />
                            </div>
                            <div>
                                <Text className="mb-1 block text-xs text-slate-500">拟合窗口半径（px）</Text>
                                <InputNumber min={3} max={500} className="w-full" value={config.fitWindowRadius}
                                    onChange={(value) => updateConfig("fitWindowRadius", Number(value ?? 12))} />
                            </div>
                        </div>
                        <Text className="mt-3 block text-xs leading-5 text-slate-500">
                            突出度用于排除只比邻近谷底略高的微小起伏；SNR 用于排除随机噪声；峰间距离避免同一个宽峰被重复报告。拟合窗口不宜过小，也不宜跨越两个相邻真实峰。
                        </Text>
                    </div>
                </div>
            </Card>

            {loadingData ? (
                <Card><div className="flex min-h-56 items-center justify-center"><Spin tip="读取光谱记录中…" /></div></Card>
            ) : curve ? (
                <Card title={`${sourceText(displayedSource)}曲线`} extra={<Tag color={displayedSource === "PREPROCESSED" ? "cyan" : "blue"}>{displayedSource}</Tag>}>
                    <SpectrumCurve spectrum={curve} markers={peakMarkers} />
                </Card>
            ) : null}

            {analysis && (
                <Card title="谱峰拟合结果" extra={<Tag color={analysis.source === "PREPROCESSED" ? "cyan" : "blue"}>{sourceText(analysis.source)}</Tag>}>
                    <div className="mb-4 grid gap-3 sm:grid-cols-2 xl:grid-cols-5">
                        <div className="rounded-lg bg-slate-50 p-3"><Text type="secondary">候选峰数</Text><div className="mt-1 text-lg font-semibold">{analysis.candidatePeakCount}</div></div>
                        <div className="rounded-lg bg-slate-50 p-3"><Text type="secondary">已拟合峰数</Text><div className="mt-1 text-lg font-semibold">{analysis.peakCount}</div></div>
                        <div className="rounded-lg bg-slate-50 p-3"><Text type="secondary">曲线动态范围</Text><div className="mt-1 text-lg font-semibold">{formatNumber(analysis.dynamicRange, 2)}</div></div>
                        <div className="rounded-lg bg-slate-50 p-3"><Text type="secondary">鲁棒噪声估计</Text><div className="mt-1 text-lg font-semibold">{formatNumber(analysis.noiseEstimate, 3)}</div></div>
                        <div className="rounded-lg bg-slate-50 p-3"><Text type="secondary">分析坐标</Text><div className="mt-1 text-lg font-semibold">pixelIndex</div></div>
                    </div>
                    <Alert className="mb-4" type="info" showIcon message={analysis.summaryMessage} description={analysis.sourceDescription} />
                    <Table<SpectrumPeakFitRecord>
                        rowKey={(record) => `${record.rank}_${record.observedPixelIndex}`}
                        columns={columns}
                        dataSource={analysis.peaks}
                        pagination={false}
                        scroll={{ x: 1100 }}
                        locale={{ emptyText: "当前阈值下没有检测到可拟合的正向峰；可检查曲线或调整突出度、SNR和峰间距。" }}
                    />
                    <div className="mt-3 flex flex-wrap gap-x-5 gap-y-1 text-xs text-slate-500">
                        <span>FWHM：拟合高斯峰半高全宽，单位为像素。</span>
                        <span>峰面积：拟合高斯峰相对局部线性基线的积分。</span>
                        <span>R² 越接近 1，说明高斯模型对该局部峰形的解释越好。</span>
                    </div>
                </Card>
            )}
        </div>
    );
};

export default SpectrumAnalysisPage;
