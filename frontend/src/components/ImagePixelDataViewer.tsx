import { Alert, Button, Input, InputNumber, Modal, Select, Space, Table, Tag, Typography } from "antd";
import { useEffect, useMemo, useRef, useState } from "react";
import type { MutableRefObject } from "react";
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

type LocateFeedback = {
    type: "success" | "warning" | "error";
    message: string;
    description?: string;
};

type LocatedImageCell = {
    x: number;
    y: number;
};

const isRowMajorReadoutOrder = (readoutOrder?: string | null) =>
    String(readoutOrder ?? "").toUpperCase() === "ROW_MAJOR";

const buildSourceOptionLabels = (
    isHdrFrame: boolean,
    isHdrDarkFrame: boolean,
    isHdrFlatFrame: boolean,
    isRowMajorFrame: boolean,
) => {
    if (isHdrDarkFrame) {
        return {
            original: "HDR暗场诊断主图 RAW16",
            calibrated: "校准后HDR暗场诊断主图 RAW16",
            processed: "处理后HDR暗场诊断主图 RAW16",
        };
    }
    if (isHdrFlatFrame) {
        return {
            original: "HDR平场诊断主图 RAW16",
            calibrated: "校准后HDR平场诊断主图 RAW16",
            processed: "处理后HDR平场诊断主图 RAW16",
        };
    }
    if (isHdrFrame) {
        return {
            original: "融合主图 RAW16",
            calibrated: "校准后融合主图 RAW16",
            processed: "处理后融合主图 RAW16",
        };
    }
    if (isRowMajorFrame) {
        return {
            original: "原图 RAW16（正常行列）",
            calibrated: "校准后 RAW16（正常行列）",
            processed: "处理后 RAW16（正常行列）",
        };
    }
    return {
        original: "重排后原图 RAW16",
        calibrated: "校准后 RAW16（已重排）",
        processed: "处理后 RAW16（已重排）",
    };
};

const resolveLoadedRange = (data: ImagePixelDataRecord) => {
    const xStart = Number.isFinite(data.xStart) ? data.xStart : 0;
    const yStart = Number.isFinite(data.yStart) ? data.yStart : 0;
    const roiWidth = data.roiWidth > 0 ? data.roiWidth : data.hexRows[0]?.trim().split(/\s{2,}/).length || data.rows[0]?.length || 0;
    const roiHeight = data.roiHeight > 0 ? data.roiHeight : data.hexRows.length || data.rows.length || 0;
    const xEnd = data.xEnd > xStart ? data.xEnd : Math.min(data.width, xStart + roiWidth);
    const yEnd = data.yEnd > yStart ? data.yEnd : Math.min(data.height, yStart + roiHeight);
    return { xStart, yStart, xEnd, yEnd };
};

const getNativeTextArea = (textAreaRef: MutableRefObject<any>) => (
    textAreaRef.current?.resizableTextArea?.textArea
    ?? textAreaRef.current?.textArea
    ?? null
);

const scrollElementIntoView = (elementId: string) => {
    window.requestAnimationFrame(() => {
        window.requestAnimationFrame(() => {
            document.getElementById(elementId)?.scrollIntoView({
                block: "center",
                inline: "center",
                behavior: "smooth",
            });
        });
    });
};

const selectHexMatrixCell = (
    textAreaRef: MutableRefObject<any>,
    hexRows: string[],
    localRow: number,
    localColumn: number,
) => {
    window.requestAnimationFrame(() => {
        window.requestAnimationFrame(() => {
            const textArea = getNativeTextArea(textAreaRef);
            const rowText = hexRows[localRow] ?? "";
            const cells = rowText.split(/\s{2,}/);
            const cellText = cells[localColumn];
            if (!textArea || !cellText) {
                return;
            }

            const rowStart = hexRows
                .slice(0, localRow)
                .reduce((sum, row) => sum + row.length + 1, 0);
            const columnStart = cells
                .slice(0, localColumn)
                .reduce((sum, cell) => sum + cell.length + 2, 0);
            const selectionStart = rowStart + columnStart;
            const selectionEnd = selectionStart + cellText.length;

            textArea.focus();
            textArea.setSelectionRange(selectionStart, selectionEnd);

            const estimatedLineHeight = 18;
            const estimatedCellWidth = 58;
            textArea.scrollTop = Math.max(
                0,
                localRow * estimatedLineHeight - textArea.clientHeight / 2,
            );
            textArea.scrollLeft = Math.max(
                0,
                localColumn * estimatedCellWidth - textArea.clientWidth / 2,
            );
        });
    });
};

/**
 * 重排后 RAW16 像素查看器。
 *
 * RAW16 像素查看器默认以 16 位字值展示两个十六进制字节，例如 00 A0。
 * 用户也可以切换成 DN 十进制窗口，或者读取整幅 800x600 的十六进制文本矩阵。
 * 原图、校准后图和处理后图都从后端 RAW 文件读取，避免把 8-bit 预览图误当成真实像素。
 * 注意：这里展示的是业务图像 RAW16。ROW_MAJOR 帧保持正常行列；GLUX1605 4-lane/HDR 帧保存为正常行列后的主图。
 */
const ImagePixelDataViewer = ({
    frame,
    defaultSource = "ORIGINAL",
    triggerLabel,
    buttonSize = "small",
}: ImagePixelDataViewerProps) => {
    const captureScene = String(frame.captureScene || "").toUpperCase();
    const isHdrFrame = captureScene === "HDR";
    const isHdrDarkFrame = captureScene === "HDR_DARK";
    const isHdrFlatFrame = captureScene === "HDR_FLAT";
    const isRowMajorFrame = isRowMajorReadoutOrder(frame.readoutOrder);
    const effectiveTriggerLabel = triggerLabel ?? (
        isHdrDarkFrame
            ? "查看HDR暗场诊断RAW16像素"
            : isHdrFlatFrame
            ? "查看HDR平场诊断RAW16像素"
            : isHdrFrame
            ? "查看融合主图RAW16像素"
            : isRowMajorFrame
              ? "查看正常行列RAW16像素"
              : "查看重排后RAW16像素"
    );
    const modalTitle = isHdrDarkFrame
        ? "HDR 暗场诊断主图 RAW16 像素数据"
        : isHdrFlatFrame
        ? "HDR 平场诊断主图 RAW16 像素数据"
        : isHdrFrame
        ? "HDR 融合主图 RAW16 像素数据"
        : isRowMajorFrame
          ? "ROW_MAJOR 正常行列 RAW16 像素数据"
          : "重排后 RAW16 像素数据";
    const modalMessage = isHdrDarkFrame
        ? "查看 HDR 暗场诊断合成主图的 RAW16 两字节像素"
        : isHdrFlatFrame
        ? "查看 HDR 平场诊断合成主图的 RAW16 两字节像素"
        : isHdrFrame
        ? "查看 HDR 融合主图的 RAW16 两字节像素"
        : isRowMajorFrame
          ? "查看 ROW_MAJOR 读出下的正常行列 RAW16 两字节像素"
        : "查看已重排为正常行列坐标的 RAW16 两字节像素";
    const modalDescription = isHdrDarkFrame
        ? "这里读取的是 HDR 暗场样本保存的诊断合成主图 raw16le.bin；HG_DARK/LG_DARK 两个输入平面已经单独保存，主图仅用于预览和审计，不作为普通HDR光谱融合结果。默认按 16 位字值展示为高字节在前，例如 DN=0x00A0 显示 00 A0；如果切换为 RAW 文件字节序，则按 raw16le.bin 的小端落盘顺序显示，例如 A0 00。"
        : isHdrFlatFrame
        ? "这里读取的是 HDR 平场样本保存的诊断合成主图 raw16le.bin；HG_FLAT/LG_FLAT 两个输入平面已经单独保存，主图仅用于预览和审计，不作为普通HDR光谱融合结果。默认按 16 位字值展示为高字节在前，例如 DN=0x00A0 显示 00 A0；如果切换为 RAW 文件字节序，则按 raw16le.bin 的小端落盘顺序显示，例如 A0 00。"
        : isHdrFrame
        ? "这里读取的是 HDR 融合后保存为主图的 raw16le.bin、calibrated/raw16le.bin 或 processed/raw16le.bin；HG/LG 两个输入平面已经先按芯片 Figure 42 顺序转换成正常行列，再由 Java 端按 HDR 融合规则生成这张主图。默认按 16 位字值展示为高字节在前，例如 DN=0x00A0 显示 00 A0；如果切换为 RAW 文件字节序，则按 raw16le.bin 的小端落盘顺序显示，例如 A0 00。"
        : isRowMajorFrame
          ? "这里读取的是 raw16le.bin、calibrated/raw16le.bin 或 processed/raw16le.bin。当前帧读出顺序为 ROW_MAJOR，FPGA payload 本身就是正常行列顺序，native 只做 RAW16 低 12 位解析和保存，像素位置保持不变。默认按 16 位字值展示为高字节在前，例如 DN=0x00A0 显示 00 A0；如果切换为 RAW 文件字节序，则按 raw16le.bin 的小端落盘顺序显示，例如 A0 00。"
        : "这里读取的是 raw16le.bin、calibrated/raw16le.bin 或 processed/raw16le.bin，均已按芯片读出顺序重排成正常二维坐标；FPGA 直接输出的跳跃/交织 payload 另存为 fpga_payload.bin。默认按 16 位字值展示为高字节在前，例如 DN=0x00A0 显示 00 A0；如果切换为 RAW 文件字节序，则按 raw16le.bin 的小端落盘顺序显示，例如 A0 00。";
    const sourceOptionLabels = buildSourceOptionLabels(isHdrFrame, isHdrDarkFrame, isHdrFlatFrame, isRowMajorFrame);
    const hasCalibratedRaw = Boolean(frame.calibratedImageDataUrl);
    const hasProcessedRaw = Boolean(frame.processedImageDataUrl);
    const normalizeAvailableSource = (source: ImagePixelSourceMode): ImagePixelSourceMode => {
        if (source === "PROCESSED" && hasProcessedRaw) {
            return "PROCESSED";
        }
        if (source === "CALIBRATED" && hasCalibratedRaw) {
            return "CALIBRATED";
        }
        return "ORIGINAL";
    };
    const [visible, setVisible] = useState(false);
    const [sourceMode, setSourceMode] = useState<ImagePixelSourceMode>(
        normalizeAvailableSource(defaultSource),
    );
    const [displayFormat, setDisplayFormat] = useState<ImagePixelDisplayFormat>("HEX_WORD");
    const [xStart, setXStart] = useState(0);
    const [yStart, setYStart] = useState(0);
    const [roiWidth, setRoiWidth] = useState(DEFAULT_WINDOW_SIZE);
    const [roiHeight, setRoiHeight] = useState(DEFAULT_WINDOW_SIZE);
    const [loading, setLoading] = useState(false);
    const [pixelData, setPixelData] = useState<ImagePixelDataRecord | null>(null);
    const [errorMessage, setErrorMessage] = useState<string | null>(null);
    const [locateX, setLocateX] = useState(0);
    const [locateY, setLocateY] = useState(0);
    const [locateFeedback, setLocateFeedback] = useState<LocateFeedback | null>(null);
    const [locatedCell, setLocatedCell] = useState<LocatedImageCell | null>(null);
    const hexTextAreaRef = useRef<any>(null);

    const resolvedDefaultSource = normalizeAvailableSource(defaultSource);

    const requestPixels = async (
        requestSourceMode: ImagePixelSourceMode,
        requestXStart: number,
        requestYStart: number,
        requestWidth: number,
        requestHeight: number,
        requestDisplayFormat: ImagePixelDisplayFormat,
        requestFullFrame = false,
    ) => {
        if (requestSourceMode === "CALIBRATED" && !hasCalibratedRaw) {
            setErrorMessage("当前图像还没有校准后 RAW16 数据；只有采集时启用了校准包才会生成。");
            return;
        }
        if (requestSourceMode === "PROCESSED" && !hasProcessedRaw) {
            setErrorMessage("当前图像还没有处理后 RAW16 数据，请先完成图像处理。");
            return;
        }
        setLoading(true);
        setErrorMessage(null);
        setLocateFeedback(null);
        setLocatedCell(null);
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
        setLocateX(0);
        setLocateY(0);
        setPixelData(null);
        setErrorMessage(null);
        setLocateFeedback(null);
        setLocatedCell(null);
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
                onCell: (record: any) => ({
                    className:
                        locatedCell?.x === x && locatedCell?.y === record.rowLabel
                            ? "bg-amber-100"
                            : "",
                }),
                render: (value: number, record: any) => {
                    const isLocated = locatedCell?.x === x && locatedCell?.y === record.rowLabel;
                    return (
                        <span
                            id={`raw16-pixel-cell-${x}-${record.rowLabel}`}
                            className={`font-mono text-xs ${isLocated ? "rounded bg-amber-300 px-1 font-semibold text-slate-900" : ""}`}
                        >
                            {value}
                        </span>
                    );
                },
            });
        }
        return columns;
    }, [locatedCell, pixelData]);

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

    const loadedRange = useMemo(() => (pixelData ? resolveLoadedRange(pixelData) : null), [pixelData]);

    const displayFormatLabel = (format: string) => {
        if (format === "HEX_FILE") {
            return "RAW小端字节";
        }
        if (format === "HEX_WORD") {
            return "16位字节";
        }
        return "低12位DN";
    };

    const locatePixelValue = () => {
        if (!pixelData) {
            setLocateFeedback({
                type: "warning",
                message: "请先读取像素数据",
                description: "定位是在当前已经加载到弹窗里的像素窗口/完整矩阵内完成的。",
            });
            setLocatedCell(null);
            return;
        }

        const x = Number(locateX);
        const y = Number(locateY);
        if (!Number.isInteger(x) || !Number.isInteger(y)) {
            setLocateFeedback({
                type: "error",
                message: "请输入整数行列坐标",
            });
            setLocatedCell(null);
            return;
        }

        if (x < 0 || x >= pixelData.width || y < 0 || y >= pixelData.height) {
            setLocateFeedback({
                type: "error",
                message: `坐标 (${x}, ${y}) 超出图像尺寸`,
                description: `当前图像尺寸为 ${pixelData.width} × ${pixelData.height}，x 范围 0~${pixelData.width - 1}，y 范围 0~${pixelData.height - 1}。`,
            });
            setLocatedCell(null);
            return;
        }

        const range = resolveLoadedRange(pixelData);
        if (x < range.xStart || x >= range.xEnd || y < range.yStart || y >= range.yEnd) {
            setLocateFeedback({
                type: "warning",
                message: `坐标 (${x}, ${y}) 不在当前已读取范围内`,
                description: `当前已读取范围为 x=[${range.xStart}, ${range.xEnd})，y=[${range.yStart}, ${range.yEnd})。请调整窗口后重新读取，或者读取完整 HEX 矩阵后再定位。`,
            });
            setLocatedCell(null);
            return;
        }

        const localRow = y - range.yStart;
        const localColumn = x - range.xStart;
        setLocatedCell({ x, y });
        if (pixelData.hexRows.length > 0) {
            const cells = pixelData.hexRows[localRow]?.trim().split(/\s{2,}/) ?? [];
            const value = cells[localColumn];
            if (!value) {
                setLocateFeedback({
                    type: "error",
                    message: "定位失败",
                    description: "当前 HEX 文本矩阵中没有找到对应单元，请重新读取像素数据。",
                });
                setLocatedCell(null);
                return;
            }
            setLocateFeedback({
                type: "success",
                message: `图像坐标 (${x}, ${y}) = ${value}`,
                description: `已在下方 HEX 像素矩阵中选中该两字节单元；来源：${pixelData.sourceLabel}；格式：${displayFormatLabel(String(pixelData.displayFormat))}；局部位置：第 ${localRow + 1} 行、第 ${localColumn + 1} 列。`,
            });
            selectHexMatrixCell(hexTextAreaRef, pixelData.hexRows, localRow, localColumn);
            return;
        }

        const value = pixelData.rows[localRow]?.[localColumn];
        if (value === undefined) {
            setLocateFeedback({
                type: "error",
                message: "定位失败",
                description: "当前 DN 表格中没有找到对应单元，请重新读取像素数据。",
            });
            setLocatedCell(null);
            return;
        }
        setLocateFeedback({
            type: "success",
            message: `图像坐标 (${x}, ${y}) = ${value}`,
            description: `已在下方 DN 像素表格中高亮该单元格；来源：${pixelData.sourceLabel}；格式：低12位DN；局部位置：第 ${localRow + 1} 行、第 ${localColumn + 1} 列。`,
        });
        scrollElementIntoView(`raw16-pixel-cell-${x}-${y}`);
    };

    return (
        <>
            <Button size={buttonSize} onClick={() => setVisible(true)}>
                {effectiveTriggerLabel}
            </Button>
            <Modal
                title={modalTitle}
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
                        message={modalMessage}
                        description={modalDescription}
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
                                    { label: sourceOptionLabels.original, value: "ORIGINAL" },
                                    {
                                        label: sourceOptionLabels.calibrated,
                                        value: "CALIBRATED",
                                        disabled: !hasCalibratedRaw,
                                    },
                                    {
                                        label: sourceOptionLabels.processed,
                                        value: "PROCESSED",
                                        disabled: !hasProcessedRaw,
                                    },
                                ]}
                            />
                            {(!hasCalibratedRaw || !hasProcessedRaw) && (
                                <Text className="mt-1 block text-[11px] text-slate-400">
                                    校准后数据需采集时启用校准包；处理后数据需完成图像修复。
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
                            <div className="flex flex-wrap items-end gap-3">
                                <Space wrap>
                                    <Button type="primary" loading={loading} onClick={loadPixels}>
                                        读取当前窗口
                                    </Button>
                                    <Button loading={loading} onClick={loadFullFrameHexPixels}>
                                        读取完整 {frame.width}×{frame.height} HEX矩阵
                                    </Button>
                                </Space>
                                <div className="flex flex-wrap items-end gap-2 rounded-md border border-slate-200 bg-white px-2 py-1.5">
                                    <div>
                                        <Text className="mb-1 block text-[11px] text-slate-500">定位 x</Text>
                                        <InputNumber
                                            size="small"
                                            min={0}
                                            max={Math.max(frame.width - 1, 0)}
                                            value={locateX}
                                            onChange={(value) => setLocateX(Number(value ?? 0))}
                                        />
                                    </div>
                                    <div>
                                        <Text className="mb-1 block text-[11px] text-slate-500">定位 y</Text>
                                        <InputNumber
                                            size="small"
                                            min={0}
                                            max={Math.max(frame.height - 1, 0)}
                                            value={locateY}
                                            onChange={(value) => setLocateY(Number(value ?? 0))}
                                        />
                                    </div>
                                    <Button size="small" onClick={locatePixelValue}>
                                        定位像素值
                                    </Button>
                                </div>
                                <Text className="text-xs text-slate-500">
                                    窗口模式最多 {MAX_WINDOW_SIZE} × {MAX_WINDOW_SIZE}；定位只在当前已读取范围内生效。
                                </Text>
                            </div>
                        </div>
                    </div>

                    {errorMessage && <Alert type="error" showIcon message={errorMessage} />}
                    {locateFeedback && (
                        <Alert
                            type={locateFeedback.type}
                            showIcon
                            message={locateFeedback.message}
                            description={locateFeedback.description}
                        />
                    )}

                    {pixelData && (
                        <div className="space-y-3">
                            <div className="flex flex-wrap items-center gap-2">
                                <Tag
                                    color={
                                        pixelData.sourceMode === "PROCESSED"
                                            ? "green"
                                            : pixelData.sourceMode === "CALIBRATED"
                                              ? "cyan"
                                              : "blue"
                                    }
                                >
                                    {pixelData.sourceLabel}
                                </Tag>
                                <Tag color="default">
                                    全图 {pixelData.width} × {pixelData.height}
                                </Tag>
                                <Tag color="purple">
                                    ROI [{loadedRange?.xStart ?? pixelData.xStart}, {loadedRange?.xEnd ?? pixelData.xEnd}) × [{loadedRange?.yStart ?? pixelData.yStart},{" "}
                                    {loadedRange?.yEnd ?? pixelData.yEnd})
                                </Tag>
                                <Tag color="geekblue">
                                    {pixelData.pixelFormat} · 容器 {pixelData.storageBitDepth} bit · 有效{" "}
                                    {pixelData.effectiveBitDepth} bit · 文件序 {pixelData.rawFileByteOrder}
                                </Tag>
                                <Tag color="cyan">
                                    {pixelData.spatialOrder}
                                </Tag>
                                {pixelData.readoutOrder && (
                                    <Tag color="volcano">
                                        读出顺序 {pixelData.readoutOrder}
                                    </Tag>
                                )}
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
                            <Alert
                                type="info"
                                showIcon
                                message={pixelData.sourceLabel}
                                description={pixelData.sourceDescription}
                            />

                            {pixelData.hexRows.length > 0 ? (
                                <div className="space-y-2">
                                    <Text className="block text-xs text-slate-500">
                                        {isRowMajorReadoutOrder(pixelData.readoutOrder)
                                            ? "每一行对应 ROW_MAJOR 正常行列图像的一个 y 坐标；相邻像素用两个空格分隔；每个像素内部是两个字节。"
                                            : "每一行对应重排后图像的一个 y 坐标；相邻像素用两个空格分隔；每个像素内部是两个字节。"}
                                    </Text>
                                    <Input.TextArea
                                        ref={hexTextAreaRef}
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
