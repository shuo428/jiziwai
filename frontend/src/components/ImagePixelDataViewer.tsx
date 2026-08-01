import { Alert, Button, Input, InputNumber, Modal, Select, Space, Table, Tag, Typography } from "antd";
import { useEffect, useMemo, useState } from "react";
import { jniBridgeService } from "../service/jniBridgeService";
import type {
    ImageFrameRecord,
    ImagePixelDataRecord,
    ImagePixelDisplayFormat,
    ImagePixelSourceMode,
} from "../types/jni";

const { Text } = Typography;

interface ImagePixelDataViewerProps {
    frame: ImageFrameRecord;
    defaultSource?: ImagePixelSourceMode;
    triggerLabel?: string;
    buttonSize?: "small" | "middle" | "large";
}

const DEFAULT_WINDOW_SIZE = 16;
const MAX_WINDOW_SIZE = 64;

/**
 * RAW16 像素查看器。
 *
 * RAW16 像素查看器默认以 16 位字值展示两个十六进制字节，例如 00 A0。
 * 用户也可以切换成 DN 十进制窗口，或者读取整幅 800x600 的十六进制文本矩阵。
 * 原图和处理后图都从后端 RAW 文件读取，避免把 8-bit 预览图误当成真实像素。
 */
const ImagePixelDataViewer = ({
    frame,
    defaultSource = "ORIGINAL",
    triggerLabel = "查看16位像素数据",
    buttonSize = "small",
}: ImagePixelDataViewerProps) => {
    const hasProcessedRaw = Boolean(frame.processedImageDataUrl);
    const [visible, setVisible] = useState(false);
    const [sourceMode, setSourceMode] = useState<ImagePixelSourceMode>(
        defaultSource === "PROCESSED" && hasProcessedRaw ? "PROCESSED" : "ORIGINAL",
    );
    const [displayFormat, setDisplayFormat] = useState<ImagePixelDisplayFormat>("HEX_WORD");
    const [xStart, setXStart] = useState(0);
    const [yStart, setYStart] = useState(0);
    const [roiWidth, setRoiWidth] = useState(DEFAULT_WINDOW_SIZE);
    const [roiHeight, setRoiHeight] = useState(DEFAULT_WINDOW_SIZE);
    const [loading, setLoading] = useState(false);
    const [pixelData, setPixelData] = useState<ImagePixelDataRecord | null>(null);
    const [errorMessage, setErrorMessage] = useState<string | null>(null);

    const resolvedDefaultSource = defaultSource === "PROCESSED" && hasProcessedRaw ? "PROCESSED" : "ORIGINAL";

    const requestPixels = async (
        requestSourceMode: ImagePixelSourceMode,
        requestXStart: number,
        requestYStart: number,
        requestWidth: number,
        requestHeight: number,
        requestDisplayFormat: ImagePixelDisplayFormat,
        requestFullFrame = false,
    ) => {
        if (requestSourceMode === "PROCESSED" && !hasProcessedRaw) {
            setErrorMessage("当前图像还没有处理后 RAW16 数据，请先完成图像处理。");
            return;
        }
        setLoading(true);
        setErrorMessage(null);
        try {
            const data = await jniBridgeService.getImagePixels(frame.id, {
                source: requestSourceMode,
                format: requestDisplayFormat,
                fullFrame: requestFullFrame,
                xStart: requestXStart,
                yStart: requestYStart,
                width: requestWidth,
                height: requestHeight,
            });
            setPixelData(data);
        } catch (error: any) {
            setPixelData(null);
            setErrorMessage(error?.message || "读取 RAW16 像素失败");
        } finally {
            setLoading(false);
        }
    };

    const loadPixels = async () => {
        await requestPixels(sourceMode, xStart, yStart, roiWidth, roiHeight, displayFormat, false);
    };

    const loadFullFrameHexPixels = async () => {
        const hexFormat = displayFormat === "HEX_FILE" ? "HEX_FILE" : "HEX_WORD";
        setDisplayFormat(hexFormat);
        await requestPixels(sourceMode, 0, 0, frame.width, frame.height, hexFormat, true);
    };

    useEffect(() => {
        if (!visible) {
            return;
        }
        const defaultWidth = Math.min(DEFAULT_WINDOW_SIZE, Math.max(frame.width, 1));
        const defaultHeight = Math.min(DEFAULT_WINDOW_SIZE, Math.max(frame.height, 1));
        setSourceMode(resolvedDefaultSource);
        setDisplayFormat("HEX_WORD");
        setXStart(0);
        setYStart(0);
        setRoiWidth(defaultWidth);
        setRoiHeight(defaultHeight);
        setPixelData(null);
        setErrorMessage(null);
        requestPixels(resolvedDefaultSource, 0, 0, defaultWidth, defaultHeight, "HEX_WORD", false);
        // 只在打开弹窗/切换图片时自动读取默认窗口；调整 ROI 后由用户点击“读取像素”刷新。
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [frame.id, frame.width, frame.height, resolvedDefaultSource, visible]);

    const tableColumns = useMemo(() => {
        const columns: any[] = [
            {
                title: "y \\ x",
                dataIndex: "rowLabel",
                key: "rowLabel",
                fixed: "left",
                width: 84,
                render: (value: number) => <span className="font-mono text-xs font-semibold">{value}</span>,
            },
        ];
        if (!pixelData || pixelData.rows.length === 0) {
            return columns;
        }
        for (let columnIndex = 0; columnIndex < pixelData.roiWidth; columnIndex++) {
            const x = pixelData.xStart + columnIndex;
            columns.push({
                title: String(x),
                dataIndex: `c${columnIndex}`,
                key: `c${columnIndex}`,
                width: 72,
                align: "right",
                render: (value: number) => <span className="font-mono text-xs">{value}</span>,
            });
        }
        return columns;
    }, [pixelData]);

    const tableRows = useMemo(() => {
        if (!pixelData || pixelData.rows.length === 0) {
            return [];
        }
        return pixelData.rows.map((row, rowIndex) => {
            const record: Record<string, number | string> = {
                key: String(pixelData.yStart + rowIndex),
                rowLabel: pixelData.yStart + rowIndex,
            };
            row.forEach((value, columnIndex) => {
                record[`c${columnIndex}`] = value;
            });
            return record;
        });
    }, [pixelData]);

    return (
        <>
            <Button size={buttonSize} onClick={() => setVisible(true)}>
                {triggerLabel}
            </Button>
            <Modal
                title="RAW16 原始像素数据"
                open={visible}
                onCancel={() => setVisible(false)}
                footer={[
                    <Button key="close" onClick={() => setVisible(false)}>
                        关闭
                    </Button>,
                ]}
                width="92vw"
                style={{ maxWidth: 1180, top: 28 }}
                destroyOnClose
            >
                <div className="space-y-4">
                    <Alert
                        type="info"
                        showIcon
                        message="查看真正 RAW16 两字节像素"
                        description="默认按 16 位字值展示为高字节在前，例如 DN=0x00A0 显示 00 A0；如果切换为 RAW 文件字节序，则按 raw16le.bin 的小端落盘顺序显示，例如 A0 00。"
                    />

                    <div className="grid gap-3 rounded-lg border border-slate-200 bg-slate-50 p-3 md:grid-cols-8">
                        <div className="md:col-span-2">
                            <Text className="mb-1 block text-xs text-slate-500">像素来源</Text>
                            <Select<ImagePixelSourceMode>
                                className="w-full"
                                value={sourceMode}
                                onChange={(value) => {
                                    setSourceMode(value);
                                    setPixelData(null);
                                    setErrorMessage(null);
                                }}
                                options={[
                                    { label: "原图 RAW16", value: "ORIGINAL" },
                                    {
                                        label: "处理后 RAW16",
                                        value: "PROCESSED",
                                        disabled: !hasProcessedRaw,
                                    },
                                ]}
                            />
                            {!hasProcessedRaw && (
                                <Text className="mt-1 block text-[11px] text-slate-400">
                                    处理后数据需要先完成图像修复。
                                </Text>
                            )}
                        </div>
                        <div className="md:col-span-2">
                            <Text className="mb-1 block text-xs text-slate-500">显示格式</Text>
                            <Select<ImagePixelDisplayFormat>
                                className="w-full"
                                value={displayFormat}
                                onChange={(value) => {
                                    setDisplayFormat(value);
                                    setPixelData(null);
                                    setErrorMessage(null);
                                }}
                                options={[
                                    { label: "16位字节：00 A0", value: "HEX_WORD" },
                                    { label: "RAW小端字节：A0 00", value: "HEX_FILE" },
                                    { label: "低12位DN：160", value: "DN" },
                                ]}
                            />
                        </div>
                        <div>
                            <Text className="mb-1 block text-xs text-slate-500">xStart</Text>
                            <InputNumber
                                className="w-full"
                                min={0}
                                max={Math.max(frame.width - 1, 0)}
                                value={xStart}
                                onChange={(value) => setXStart(Number(value ?? 0))}
                            />
                        </div>
                        <div>
                            <Text className="mb-1 block text-xs text-slate-500">yStart</Text>
                            <InputNumber
                                className="w-full"
                                min={0}
                                max={Math.max(frame.height - 1, 0)}
                                value={yStart}
                                onChange={(value) => setYStart(Number(value ?? 0))}
                            />
                        </div>
                        <div>
                            <Text className="mb-1 block text-xs text-slate-500">宽度</Text>
                            <InputNumber
                                className="w-full"
                                min={1}
                                max={MAX_WINDOW_SIZE}
                                value={roiWidth}
                                onChange={(value) => setRoiWidth(Number(value ?? DEFAULT_WINDOW_SIZE))}
                            />
                        </div>
                        <div>
                            <Text className="mb-1 block text-xs text-slate-500">高度</Text>
                            <InputNumber
                                className="w-full"
                                min={1}
                                max={MAX_WINDOW_SIZE}
                                value={roiHeight}
                                onChange={(value) => setRoiHeight(Number(value ?? DEFAULT_WINDOW_SIZE))}
                            />
                        </div>
                        <div className="md:col-span-8">
                            <Space wrap>
                                <Button type="primary" loading={loading} onClick={loadPixels}>
                                    读取当前窗口
                                </Button>
                                <Button loading={loading} onClick={loadFullFrameHexPixels}>
                                    读取完整 {frame.width}×{frame.height} HEX矩阵
                                </Button>
                                <Text className="text-xs text-slate-500">
                                    窗口模式最多 {MAX_WINDOW_SIZE} × {MAX_WINDOW_SIZE}；完整模式以文本矩阵返回全部两字节像素。
                                </Text>
                            </Space>
                        </div>
                    </div>

                    {errorMessage && <Alert type="error" showIcon message={errorMessage} />}

                    {pixelData && (
                        <div className="space-y-3">
                            <div className="flex flex-wrap items-center gap-2">
                                <Tag color={pixelData.sourceMode === "PROCESSED" ? "green" : "blue"}>
                                    {pixelData.sourceMode === "PROCESSED" ? "处理后 RAW16" : "原图 RAW16"}
                                </Tag>
                                <Tag color="default">
                                    全图 {pixelData.width} × {pixelData.height}
                                </Tag>
                                <Tag color="purple">
                                    ROI [{pixelData.xStart}, {pixelData.xEnd}) × [{pixelData.yStart},{" "}
                                    {pixelData.yEnd})
                                </Tag>
                                <Tag color="geekblue">
                                    {pixelData.pixelFormat} · 容器 {pixelData.storageBitDepth} bit · 有效{" "}
                                    {pixelData.effectiveBitDepth} bit · 文件序 {pixelData.rawFileByteOrder}
                                </Tag>
                                <Tag color={pixelData.displayFormat === "DN" ? "blue" : "magenta"}>
                                    {pixelData.displayFormat === "HEX_FILE"
                                        ? "RAW小端字节"
                                        : pixelData.displayFormat === "HEX_WORD"
                                          ? "16位字节"
                                          : "低12位DN"}
                                </Tag>
                                <Tag color="orange">
                                    min/max/mean = {pixelData.pixelMin} / {pixelData.pixelMax} /{" "}
                                    {pixelData.pixelMean.toFixed(2)}
                                </Tag>
                            </div>

                            {pixelData.hexRows.length > 0 ? (
                                <div className="space-y-2">
                                    <Text className="text-xs text-slate-500">
                                        每一行对应图像的一个 y 坐标；相邻像素用两个空格分隔；每个像素内部是两个字节。
                                    </Text>
                                    <Input.TextArea
                                        value={pixelData.hexRows.join("\n")}
                                        readOnly
                                        className="font-mono text-xs"
                                        autoSize={false}
                                        style={{ height: pixelData.fullFrame ? 520 : 360, whiteSpace: "pre" }}
                                    />
                                </div>
                            ) : (
                                <Table
                                    size="small"
                                    bordered
                                    pagination={false}
                                    rowKey="key"
                                    columns={tableColumns}
                                    dataSource={tableRows}
                                    scroll={{
                                        x: Math.max((pixelData.roiWidth || DEFAULT_WINDOW_SIZE) * 72 + 84, 760),
                                        y: 430,
                                    }}
                                />
                            )}
                        </div>
                    )}
                </div>
            </Modal>
        </>
    );
};

export default ImagePixelDataViewer;
