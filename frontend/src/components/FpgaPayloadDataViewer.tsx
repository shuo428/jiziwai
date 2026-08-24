import { Alert, Button, Input, InputNumber, Modal, Select, Space, Table, Tag, Typography } from "antd";
import { useEffect, useMemo, useRef, useState } from "react";
import type { MutableRefObject } from "react";
import { jniBridgeService } from "../service/jniBridgeService";
import type { FpgaPayloadPixelDataRecord, ImageFrameRecord } from "../types/jni";

const { Text } = Typography;

type PayloadViewMode = "mapping" | "matrix";

type LocateFeedback = {
    type: "success" | "warning" | "error";
    message: string;
    description?: string;
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
    rowIndex: number,
    columnIndex: number,
) => {
    window.requestAnimationFrame(() => {
        window.requestAnimationFrame(() => {
            const textArea = getNativeTextArea(textAreaRef);
            const rowText = hexRows[rowIndex] ?? "";
            const cells = rowText.split(/\s{2,}/);
            const cellText = cells[columnIndex];
            if (!textArea || !cellText) {
                return;
            }

            const rowStart = hexRows
                .slice(0, rowIndex)
                .reduce((sum, row) => sum + row.length + 1, 0);
            const columnStart = cells
                .slice(0, columnIndex)
                .reduce((sum, cell) => sum + cell.length + 2, 0);
            const selectionStart = rowStart + columnStart;
            const selectionEnd = selectionStart + cellText.length;

            textArea.focus();
            textArea.setSelectionRange(selectionStart, selectionEnd);

            const estimatedLineHeight = 18;
            const estimatedCellWidth = 58;
            textArea.scrollTop = Math.max(
                0,
                rowIndex * estimatedLineHeight - textArea.clientHeight / 2,
            );
            textArea.scrollLeft = Math.max(
                0,
                columnIndex * estimatedCellWidth - textArea.clientWidth / 2,
            );
        });
    });
};

interface FpgaPayloadDataViewerProps {
    frame: ImageFrameRecord;
    triggerLabel?: string;
    buttonSize?: "small" | "middle" | "large";
}

const DEFAULT_PAYLOAD_WINDOW_SIZE = 128;

const isHdrLikeFrame = (frame: ImageFrameRecord) =>
    ["HDR", "HDR_DARK", "HDR_FLAT"].includes(String(frame.captureScene || "").toUpperCase());

const isHdrDarkFrame = (frame: ImageFrameRecord) =>
    String(frame.captureScene || "").toUpperCase() === "HDR_DARK";

const isHdrFlatFrame = (frame: ImageFrameRecord) =>
    String(frame.captureScene || "").toUpperCase() === "HDR_FLAT";

const resolvePayloadPlaneName = (frame: ImageFrameRecord, planeIndex: number) => {
    if (isHdrLikeFrame(frame)) {
        if (planeIndex === 0) {
            if (isHdrDarkFrame(frame)) return "HG_DARK";
            if (isHdrFlatFrame(frame)) return "HG_FLAT";
            return "HG";
        }
        if (planeIndex === 1) {
            if (isHdrDarkFrame(frame)) return "LG_DARK";
            if (isHdrFlatFrame(frame)) return "LG_FLAT";
            return "LG";
        }
        return `HDR-${planeIndex + 1}`;
    }
    return "SINGLE";
};

const resolvePayloadPlaneCountFromFrame = (frame: ImageFrameRecord) => {
    const planePixelCount = Math.max((frame.width || 0) * (frame.height || 0), 0);
    const payloadPixelCount = Math.floor(Math.max(frame.payloadLength || 0, 0) / 2);
    if (planePixelCount <= 0 || payloadPixelCount <= 0) {
        return 1;
    }
    return Math.max(1, Math.floor(payloadPixelCount / planePixelCount));
};

const isRowMajorReadoutOrder = (readoutOrder?: string | null) =>
    String(readoutOrder ?? "").toUpperCase() === "ROW_MAJOR";

/**
 * FPGA 原始 payload 查看器。
 *
 * 这个组件展示 fpga_payload.bin，而不是 raw16le.bin。
 * ROW_MAJOR 下 payload 行列就是正常图像坐标；GLUX1605 4-lane 下 imageX/imageY 才是重排后的正常图像坐标。
 */
const FpgaPayloadDataViewer = ({
    frame,
    triggerLabel = "查看FPGA原始payload",
    buttonSize = "small",
}: FpgaPayloadDataViewerProps) => {
    const hasPayload = Boolean(frame.fpgaPayloadStorageUri);
    const isRowMajorFrame = isRowMajorReadoutOrder(frame.readoutOrder);
    const [visible, setVisible] = useState(false);
    const [payloadStart, setPayloadStart] = useState(0);
    const [payloadCount, setPayloadCount] = useState(DEFAULT_PAYLOAD_WINDOW_SIZE);
    const [viewMode, setViewMode] = useState<PayloadViewMode>("mapping");
    const [loading, setLoading] = useState(false);
    const [mappingData, setMappingData] = useState<FpgaPayloadPixelDataRecord | null>(null);
    const [matrixData, setMatrixData] = useState<FpgaPayloadPixelDataRecord | null>(null);
    const [errorMessage, setErrorMessage] = useState<string | null>(null);
    const [locatePayloadRow, setLocatePayloadRow] = useState(0);
    const [locatePayloadColumn, setLocatePayloadColumn] = useState(0);
    const [locatePayloadPlaneIndex, setLocatePayloadPlaneIndex] = useState(0);
    const [locateFeedback, setLocateFeedback] = useState<LocateFeedback | null>(null);
    const [locatedPayloadIndex, setLocatedPayloadIndex] = useState<number | null>(null);
    const payloadMatrixTextAreaRef = useRef<any>(null);

    const fallbackPayloadPixelCount = Math.max(
        Math.floor(Math.max(frame.payloadLength || 0, 0) / 2),
        (frame.width || 0) * (frame.height || 0),
    );
    const loadedPayloadPixelCount = Math.max(
        mappingData?.payloadPixelCount ?? 0,
        matrixData?.payloadPixelCount ?? 0,
        fallbackPayloadPixelCount,
    );
    const currentPayloadPlaneCount = Math.max(
        mappingData?.payloadPlaneCount ?? 0,
        matrixData?.payloadPlaneCount ?? 0,
        resolvePayloadPlaneCountFromFrame(frame),
    );
    const currentPlanePixelCount = Math.max((frame.width || 0) * (frame.height || 0), 1);
    const maxPayloadIndex = Math.max(loadedPayloadPixelCount - 1, 0);
    const maxWindowCount = mappingData?.maxWindowCount ?? matrixData?.maxWindowCount ?? 4096;
    const planeOptions = Array.from({ length: currentPayloadPlaneCount }, (_, index) => ({
        value: index,
        label: `${resolvePayloadPlaneName(frame, index)} 平面`,
    }));

    const requestPayload = async (start: number, count: number, fullFrame = false) => {
        if (!hasPayload) {
            setErrorMessage("当前图像没有保存 FPGA 原始 payload；请重新采集新帧。");
            return;
        }
        setLoading(true);
        setErrorMessage(null);
        setLocateFeedback(null);
        setLocatedPayloadIndex(null);
        try {
            const data = await jniBridgeService.getFpgaPayloadPixels(frame.id, {
                start,
                count,
                fullFrame,
            });
            if (fullFrame) {
                setMatrixData(data);
            } else {
                setMappingData(data);
                setPayloadStart(data.payloadStart);
                setPayloadCount(Math.min(count, data.maxWindowCount));
            }
        } catch (error: any) {
            if (fullFrame) {
                setMatrixData(null);
            } else {
                setMappingData(null);
            }
            setErrorMessage(error?.message || "读取 FPGA 原始 payload 失败");
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        if (!visible) {
            return;
        }
        setPayloadStart(0);
        setPayloadCount(DEFAULT_PAYLOAD_WINDOW_SIZE);
        setViewMode("mapping");
        setLocatePayloadRow(0);
        setLocatePayloadColumn(0);
        setLocatePayloadPlaneIndex(0);
        setMappingData(null);
        setMatrixData(null);
        setErrorMessage(null);
        setLocateFeedback(null);
        setLocatedPayloadIndex(null);
        requestPayload(0, DEFAULT_PAYLOAD_WINDOW_SIZE, false);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [frame.id, visible]);

    const tableColumns = useMemo(
        () => [
            {
                title: "payloadIndex",
                dataIndex: "payloadIndex",
                key: "payloadIndex",
                fixed: "left" as const,
                width: 118,
                render: (value: number) => <span className="font-mono text-xs font-semibold">{value}</span>,
            },
            {
                title: "平面",
                dataIndex: "plane",
                key: "plane",
                width: 76,
                render: (value: string) => <Tag color={value === "HG" ? "geekblue" : value === "LG" ? "purple" : "default"}>{value}</Tag>,
            },
            {
                title: "payload行",
                dataIndex: "payloadRow",
                key: "payloadRow",
                width: 88,
            },
            {
                title: "payload列",
                dataIndex: "payloadColumn",
                key: "payloadColumn",
                width: 88,
            },
            {
                title: "lane",
                dataIndex: "lane",
                key: "lane",
                width: 72,
            },
            {
                title: "sample",
                dataIndex: "sample",
                key: "sample",
                width: 88,
            },
            {
                title: isRowMajorFrame ? "图像坐标" : "重排后坐标",
                key: "imageCoordinate",
                width: 120,
                render: (_: unknown, record: any) => (
                    <span className="font-mono text-xs">
                        ({record.imageX}, {record.imageY})
                    </span>
                ),
            },
            {
                title: "16位字节",
                dataIndex: "hexWord",
                key: "hexWord",
                width: 96,
                render: (value: string) => <span className="font-mono text-xs">{value}</span>,
            },
            {
                title: "文件小端",
                dataIndex: "hexFileBytes",
                key: "hexFileBytes",
                width: 96,
                render: (value: string) => <span className="font-mono text-xs">{value}</span>,
            },
            {
                title: "DN",
                dataIndex: "dn",
                key: "dn",
                width: 86,
            },
            {
                title: "raw16",
                dataIndex: "raw16Value",
                key: "raw16Value",
                width: 86,
            },
        ],
        [isRowMajorFrame],
    );

    const visibleTableColumns = useMemo(
        () => isRowMajorFrame
            ? tableColumns.filter((column: any) => column.key !== "lane" && column.key !== "sample")
            : tableColumns,
        [isRowMajorFrame, tableColumns],
    );

    const validatePayloadCoordinate = (data: FpgaPayloadPixelDataRecord | null) => {
        if (!data) {
            return {
                ok: false,
                payloadRow: 0,
                payloadColumn: 0,
                payloadIndex: 0,
                feedback: {
                    type: "warning" as const,
                    message: "请先读取 payload 数据",
                    description: "定位是在当前已经加载到弹窗里的 payload 窗口或完整矩阵内完成的。",
                },
            };
        }

        const payloadRow = Number(locatePayloadRow);
        const payloadColumn = Number(locatePayloadColumn);
        const planeIndex = Number(locatePayloadPlaneIndex);
        if (!Number.isInteger(payloadRow) || !Number.isInteger(payloadColumn) || !Number.isInteger(planeIndex)) {
            return {
                ok: false,
                payloadRow,
                payloadColumn,
                planeIndex,
                matrixRow: 0,
                payloadIndex: 0,
                feedback: {
                    type: "error" as const,
                    message: "请输入整数 payload 平面/行列坐标",
                },
            };
        }

        const payloadPlaneCount = Math.max(data.payloadPlaneCount ?? 1, 1);
        if (planeIndex < 0 || planeIndex >= payloadPlaneCount) {
            return {
                ok: false,
                payloadRow,
                payloadColumn,
                planeIndex,
                matrixRow: 0,
                payloadIndex: 0,
                feedback: {
                    type: "error" as const,
                    message: `payload 平面 ${planeIndex} 超出范围`,
                    description: `当前 payload 共有 ${payloadPlaneCount} 个平面，平面范围 0~${payloadPlaneCount - 1}。`,
                },
            };
        }

        if (payloadRow < 0 || payloadRow >= data.height || payloadColumn < 0 || payloadColumn >= data.width) {
            return {
                ok: false,
                payloadRow,
                payloadColumn,
                planeIndex,
                matrixRow: 0,
                payloadIndex: 0,
                feedback: {
                    type: "error" as const,
                    message: `payload 坐标 (${payloadRow}, ${payloadColumn}) 超出尺寸`,
                    description: `当前每个 payload 平面尺寸为 ${data.width} × ${data.height}，行范围 0~${data.height - 1}，列范围 0~${data.width - 1}。`,
                },
            };
        }

        const planePixelCount = data.width * data.height;
        const payloadIndex = planeIndex * planePixelCount + payloadRow * data.width + payloadColumn;
        return {
            ok: true,
            payloadRow,
            payloadColumn,
            planeIndex,
            matrixRow: planeIndex * data.height + payloadRow,
            payloadIndex,
            feedback: null,
        };
    };

    const locatePayloadMappingValue = () => {
        const validation = validatePayloadCoordinate(mappingData);
        if (!validation.ok) {
            setLocateFeedback(validation.feedback);
            setLocatedPayloadIndex(null);
            return;
        }

        const { payloadRow, payloadColumn, payloadIndex, planeIndex } = validation;
        if (!mappingData || payloadIndex < mappingData.payloadStart || payloadIndex >= mappingData.payloadEnd) {
            setLocateFeedback({
                type: "warning",
                message: `payload ${resolvePayloadPlaneName(frame, planeIndex)}(${payloadRow}, ${payloadColumn}) 不在当前已读取窗口内`,
                description: mappingData
                    ? `该坐标对应 payloadIndex=${payloadIndex}，当前窗口为 [${mappingData.payloadStart}, ${mappingData.payloadEnd})。请调整起始 payloadIndex/读取数量后重新读取。`
                    : "请先读取 payload 窗口。",
            });
            setLocatedPayloadIndex(null);
            return;
        }

        const record = mappingData.pixels.find((pixel) => pixel.payloadIndex === payloadIndex);
        if (!record) {
            setLocateFeedback({
                type: "error",
                message: "定位失败",
                description: "当前 payload 明细表中没有找到对应记录，请重新读取 payload 窗口。",
            });
            setLocatedPayloadIndex(null);
            return;
        }

        setLocatedPayloadIndex(record.payloadIndex);
        setLocateFeedback({
            type: "success",
            message: `payload ${record.plane ?? resolvePayloadPlaneName(frame, planeIndex)}(${record.payloadRow}, ${record.payloadColumn}) = ${record.hexWord}`,
            description: isRowMajorFrame
                ? `已在下方 payload 映射表中高亮该行；payloadIndex=${record.payloadIndex}；平面内索引=${record.planePixelIndex ?? "-"}；文件小端=${record.hexFileBytes}；DN=${record.dn}；raw16=${record.raw16Value}；当前为 ROW_MAJOR，payload 行列就是正常图像坐标=(${record.imageX}, ${record.imageY})。`
                : `已在下方 payload 映射表中高亮该行；payloadIndex=${record.payloadIndex}；平面内索引=${record.planePixelIndex ?? "-"}；文件小端=${record.hexFileBytes}；DN=${record.dn}；raw16=${record.raw16Value}；lane=${record.lane}；sample=${record.sample}；重排后图像坐标=(${record.imageX}, ${record.imageY})。`,
        });
        scrollElementIntoView(`fpga-payload-row-${record.payloadIndex}`);
    };

    const locatePayloadMatrixValue = () => {
        const validation = validatePayloadCoordinate(matrixData);
        if (!validation.ok) {
            setLocateFeedback(validation.feedback);
            setLocatedPayloadIndex(null);
            return;
        }

        const { payloadRow, payloadColumn, payloadIndex, planeIndex, matrixRow } = validation;
        if (!matrixData || matrixData.hexRows.length === 0) {
            setLocateFeedback({
                type: "warning",
                message: "请先读取完整 payload HEX 矩阵",
                description: "整体像素值矩阵需要先加载完整文本矩阵，才能按行列定位任意 payload 像素。",
            });
            setLocatedPayloadIndex(null);
            return;
        }

        const cells = matrixData.hexRows[matrixRow]?.trim().split(/\s{2,}/) ?? [];
        const value = cells[payloadColumn];
        if (!value) {
            setLocateFeedback({
                type: "error",
                message: "定位失败",
                description: "当前 payload HEX 矩阵中没有找到对应单元，请重新读取完整矩阵。",
            });
            setLocatedPayloadIndex(null);
            return;
        }

        setLocatedPayloadIndex(null);
        setLocateFeedback({
            type: "success",
            message: `payload ${resolvePayloadPlaneName(frame, planeIndex)}(${payloadRow}, ${payloadColumn}) = ${value}`,
            description: isRowMajorFrame
                ? `已在下方 payload HEX 矩阵中选中该两字节单元；payloadIndex=${payloadIndex}；矩阵文本行=${matrixRow}；当前为 ROW_MAJOR，该 payload 行列就是正常图像坐标。`
                : `已在下方 payload HEX 矩阵中选中该两字节单元；payloadIndex=${payloadIndex}；矩阵文本行=${matrixRow}；当前显示为 16 位字值高字节在前。若要看该像素重排后落点，请切换到“原始payload映射”并读取包含 payloadIndex=${payloadIndex} 的窗口。`,
        });
        selectHexMatrixCell(payloadMatrixTextAreaRef, matrixData.hexRows, matrixRow, payloadColumn);
    };

    return (
        <>
            <Button
                size={buttonSize}
                onClick={() => setVisible(true)}
                disabled={!hasPayload}
                title={hasPayload ? undefined : "当前图像没有 fpga_payload.bin，重新采集后可查看"}
            >
                {triggerLabel}
            </Button>
            <Modal
                title={isHdrLikeFrame(frame)
                    ? isHdrDarkFrame(frame)
                        ? "HDR 暗场原始 FPGA payload 像素数据"
                        : isHdrFlatFrame(frame)
                        ? "HDR 平场原始 FPGA payload 像素数据"
                        : "HDR 原始 FPGA payload 像素数据"
                    : "FPGA 原始 payload 像素数据"}
                open={visible}
                onCancel={() => setVisible(false)}
                footer={[
                    <Button key="close" onClick={() => setVisible(false)}>
                        关闭
                    </Button>,
                ]}
                width="94vw"
                style={{ maxWidth: 1280, top: 24 }}
                destroyOnClose
            >
                <div className="space-y-4">
                    <div className="flex flex-wrap gap-2 rounded-lg border border-slate-200 bg-slate-50 p-2">
                        <Button
                            type={viewMode === "mapping" ? "primary" : "default"}
                            onClick={() => setViewMode("mapping")}
                        >
                            原始payload映射
                        </Button>
                        <Button
                            type={viewMode === "matrix" ? "primary" : "default"}
                            onClick={() => setViewMode("matrix")}
                        >
                            整体像素值矩阵
                        </Button>
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

                    {viewMode === "mapping" && (
                        <div className="space-y-3">
                            <Alert
                                type="warning"
                                showIcon
                                message="这里查看的是 FPGA 直接发送的 fpga_payload.bin"
                                description={
                                    isRowMajorFrame
                                        ? currentPayloadPlaneCount > 1
                                            ? `HDR payload 按原始线性顺序保存为 ${resolvePayloadPlaneName(frame, 0)} 平面后接 ${resolvePayloadPlaneName(frame, 1)} 平面；每个平面仍是 ${frame.width} × ${frame.height}。当前读出顺序为 ROW_MAJOR，每个平面内部 payload 行列就是正常图像坐标，不需要空间重排。`
                                            : "当前读出顺序为 ROW_MAJOR，payloadIndex 按正常行列顺序递增，payload行/列就是图像 y/x 坐标；这里主要用于查看 FPGA 原始文件字节、payloadIndex 和 raw16/DN。"
                                    : currentPayloadPlaneCount > 1
                                        ? `HDR payload 按原始线性顺序保存为 ${resolvePayloadPlaneName(frame, 0)} 平面后接 ${resolvePayloadPlaneName(frame, 1)} 平面；每个平面仍是 ${frame.width} × ${frame.height}。payload列不是正常图像 x 坐标，表格中的“重排后坐标”表示 native 按 GLUX1605BSI HDR 4-lane 规则转序后，该像素最终落到该平面的正常图像 (x,y)。`
                                        : "payloadIndex 按 FPGA 原始线性顺序递增；payload列不是正常图像 x 坐标。表格中的“重排后坐标”表示 native 按 GLUX1605BSI HDR 4-lane 规则转序后，该像素最终落到正常图像的哪个 (x,y)。"
                                }
                            />

                            <div className="rounded-lg border border-slate-200 bg-slate-50 p-3">
                                <div className="grid gap-3 md:grid-cols-5">
                                    <div>
                                        <Text className="mb-1 block text-xs text-slate-500">起始 payloadIndex</Text>
                                        <InputNumber
                                            className="w-full"
                                            min={0}
                                            max={maxPayloadIndex}
                                            value={payloadStart}
                                            onChange={(value) => setPayloadStart(Number(value ?? 0))}
                                        />
                                    </div>
                                    <div>
                                        <Text className="mb-1 block text-xs text-slate-500">读取数量</Text>
                                        <InputNumber
                                            className="w-full"
                                            min={1}
                                            max={maxWindowCount}
                                            value={payloadCount}
                                            onChange={(value) =>
                                                setPayloadCount(Number(value ?? DEFAULT_PAYLOAD_WINDOW_SIZE))
                                            }
                                        />
                                    </div>
                                    <div className="md:col-span-3 flex items-end">
                                        <Space wrap>
                                            <Button
                                                type="primary"
                                                loading={loading}
                                                onClick={() => requestPayload(payloadStart, payloadCount, false)}
                                            >
                                                读取payload窗口
                                            </Button>
                                            <Button
                                                loading={loading}
                                                onClick={() =>
                                                    requestPayload(
                                                        Math.min(payloadStart + payloadCount, maxPayloadIndex),
                                                        payloadCount,
                                                        false,
                                                    )
                                                }
                                            >
                                                下一段
                                            </Button>
                                            <Text className="text-xs text-slate-500">
                                                表格窗口最多 {maxWindowCount} 个像素，
                                                {isRowMajorFrame
                                                    ? "用于查看 payload 原始字节和正常图像坐标。"
                                                    : "用于查看原始位置和重排后坐标的对应关系。"}
                                            </Text>
                                        </Space>
                                    </div>
                                    <div className="md:col-span-5 flex flex-wrap items-end gap-2 rounded-md border border-slate-200 bg-white px-2 py-1.5">
                                        {currentPayloadPlaneCount > 1 && (
                                            <div>
                                                <Text className="mb-1 block text-[11px] text-slate-500">定位平面</Text>
                                                <Select
                                                    size="small"
                                                    className="min-w-28"
                                                    value={locatePayloadPlaneIndex}
                                                    options={planeOptions}
                                                    onChange={setLocatePayloadPlaneIndex}
                                                />
                                            </div>
                                        )}
                                        <div>
                                            <Text className="mb-1 block text-[11px] text-slate-500">定位 payload行</Text>
                                            <InputNumber
                                                size="small"
                                                min={0}
                                                max={Math.max(frame.height - 1, 0)}
                                                value={locatePayloadRow}
                                                onChange={(value) => setLocatePayloadRow(Number(value ?? 0))}
                                            />
                                        </div>
                                        <div>
                                            <Text className="mb-1 block text-[11px] text-slate-500">定位 payload列</Text>
                                            <InputNumber
                                                size="small"
                                                min={0}
                                                max={Math.max(frame.width - 1, 0)}
                                                value={locatePayloadColumn}
                                                onChange={(value) => setLocatePayloadColumn(Number(value ?? 0))}
                                            />
                                        </div>
                                        <Button size="small" onClick={locatePayloadMappingValue}>
                                            定位像素值
                                        </Button>
                                        <Text className="text-xs text-slate-500">
                                            {isRowMajorFrame
                                                ? "定位只在当前 payload 窗口内生效；ROW_MAJOR 下 payload 行列就是图像坐标。"
                                                : "映射定位只在当前 payload 窗口内生效，结果会同时给出重排后的图像坐标。"}
                                        </Text>
                                    </div>
                                </div>
                            </div>

                            {mappingData && (
                                <>
                            <div className="flex flex-wrap items-center gap-2">
                                <Tag color="volcano">FPGA原始payload</Tag>
                                <Tag color="default">
                                    每平面 {mappingData.width} × {mappingData.height}
                                </Tag>
                                <Tag color="blue">
                                    {mappingData.payloadPlaneCount ?? 1} 平面 · 总像素 {mappingData.payloadPixelCount}
                                </Tag>
                                <Tag color="purple">
                                    payload [{mappingData.payloadStart}, {mappingData.payloadEnd}) /{" "}
                                    {mappingData.payloadPixelCount}
                                </Tag>
                                {isRowMajorFrame ? (
                                    <Tag color="cyan">ROW_MAJOR · 无空间重排</Tag>
                                ) : (
                                    <Tag color="cyan">
                                        planes={mappingData.payloadPlaneCount ?? 1} · lane={mappingData.laneCount} · laneWidth={mappingData.laneWidth}
                                    </Tag>
                                )}
                                <Tag color="geekblue">
                                    {mappingData.pixelFormat} · 容器 {mappingData.storageBitDepth} bit · 有效{" "}
                                    {mappingData.effectiveBitDepth} bit · 文件序 {mappingData.rawFileByteOrder}
                                </Tag>
                                <Tag color="orange">
                                    min/max/mean = {mappingData.pixelMin} / {mappingData.pixelMax} /{" "}
                                    {mappingData.pixelMean.toFixed(2)}
                                </Tag>
                            </div>
                            <Alert
                                type="info"
                                showIcon
                                message={`读出顺序：${mappingData.readoutOrder || "未记录"}`}
                                description={
                                    <div className="space-y-1">
                                        <div>{mappingData.sourceDescription}</div>
                                        {(mappingData.payloadPlaneCount ?? 1) > 1 && (
                                            <div>
                                                平面布局：payloadIndex 0~{currentPlanePixelCount - 1} 为{" "}
                                                {resolvePayloadPlaneName(frame, 0)}；payloadIndex {currentPlanePixelCount}~
                                                {Math.max(currentPlanePixelCount * 2 - 1, currentPlanePixelCount)} 为{" "}
                                                {resolvePayloadPlaneName(frame, 1)}。
                                            </div>
                                        )}
                                        <div className="break-all">
                                            存储位置：{mappingData.payloadStorageUri || "未记录"}
                                        </div>
                                        <div className="break-all">
                                            SHA-256：{mappingData.payloadSha256 || "未记录"}
                                        </div>
                                    </div>
                                }
                            />
                            <Table
                                size="small"
                                bordered
                                pagination={false}
                                rowKey="payloadIndex"
                                columns={visibleTableColumns}
                                dataSource={mappingData.pixels}
                                rowClassName={(record) =>
                                    record.payloadIndex === locatedPayloadIndex ? "bg-amber-100" : ""
                                }
                                onRow={(record) => ({
                                    id: `fpga-payload-row-${record.payloadIndex}`,
                                })}
                                scroll={{ x: 950, y: 500 }}
                            />
                                </>
                            )}
                        </div>
                    )}

                    {viewMode === "matrix" && (
                        <div className="space-y-3">
                            <Alert
                                type="info"
                                showIcon
                                message="整体像素值矩阵按 FPGA 原始 payload 行列展示"
                                description={
                                    isRowMajorFrame
                                        ? currentPayloadPlaneCount > 1
                                            ? `这个视图只展示 FPGA 原始 payload 像素值本身；当前为 ROW_MAJOR，前 ${frame.height} 行是 ${resolvePayloadPlaneName(frame, 0)} 正常行列，后 ${frame.height} 行是 ${resolvePayloadPlaneName(frame, 1)} 正常行列。`
                                            : "这个视图只展示 FPGA 原始 payload 像素值本身；当前为 ROW_MAJOR，因此该矩阵和原图 RAW16 的空间行列一致。"
                                    : currentPayloadPlaneCount > 1
                                        ? `这个视图只展示像素值本身，样式和“查看重排后RAW16像素”的完整 HEX 矩阵一致；HDR 下文本矩阵按平面连续展开：前 ${frame.height} 行是 ${resolvePayloadPlaneName(frame, 0)}，后 ${frame.height} 行是 ${resolvePayloadPlaneName(frame, 1)}。`
                                        : "这个视图只展示像素值本身，样式和“查看重排后RAW16像素”的完整 HEX 矩阵一致；它不显示 lane、sample 和重排后坐标。"
                                }
                            />

                            <div className="rounded-lg border border-slate-200 bg-slate-50 p-3">
                                <div className="flex flex-wrap items-end gap-3">
                                    <Space wrap>
                                        <Button
                                            type="primary"
                                            loading={loading}
                                            onClick={() => requestPayload(0, loadedPayloadPixelCount, true)}
                                        >
                                            读取完整payload HEX矩阵
                                        </Button>
                                    </Space>
                                    <div className="flex flex-wrap items-end gap-2 rounded-md border border-slate-200 bg-white px-2 py-1.5">
                                        {currentPayloadPlaneCount > 1 && (
                                            <div>
                                                <Text className="mb-1 block text-[11px] text-slate-500">定位平面</Text>
                                                <Select
                                                    size="small"
                                                    className="min-w-28"
                                                    value={locatePayloadPlaneIndex}
                                                    options={planeOptions}
                                                    onChange={setLocatePayloadPlaneIndex}
                                                />
                                            </div>
                                        )}
                                        <div>
                                            <Text className="mb-1 block text-[11px] text-slate-500">定位 payload行</Text>
                                            <InputNumber
                                                size="small"
                                                min={0}
                                                max={Math.max(frame.height - 1, 0)}
                                                value={locatePayloadRow}
                                                onChange={(value) => setLocatePayloadRow(Number(value ?? 0))}
                                            />
                                        </div>
                                        <div>
                                            <Text className="mb-1 block text-[11px] text-slate-500">定位 payload列</Text>
                                            <InputNumber
                                                size="small"
                                                min={0}
                                                max={Math.max(frame.width - 1, 0)}
                                                value={locatePayloadColumn}
                                                onChange={(value) => setLocatePayloadColumn(Number(value ?? 0))}
                                            />
                                        </div>
                                        <Button size="small" onClick={locatePayloadMatrixValue}>
                                            定位像素值
                                        </Button>
                                    </div>
                                    <Text className="text-xs text-slate-500">
                                        {currentPayloadPlaneCount > 1
                                            ? `每一行对应 fpga_payload.bin 的一个平面内 payload 行；完整矩阵读取后可按 HG/LG 平面 + 行列定位任意 payload 像素。`
                                            : "每一行对应 fpga_payload.bin 的一个 payload 行；完整矩阵读取后可定位任意 payload 行列。"}
                                    </Text>
                                </div>
                            </div>

                            {matrixData && (
                                <>
                                    <div className="flex flex-wrap items-center gap-2">
                                        <Tag color="volcano">FPGA原始payload</Tag>
                                        <Tag color="default">
                                            每平面 {matrixData.width} × {matrixData.height}
                                        </Tag>
                                        <Tag color="blue">
                                            矩阵 {matrixData.width} × {matrixData.height * (matrixData.payloadPlaneCount ?? 1)}
                                        </Tag>
                                        <Tag color="cyan">
                                            {matrixData.payloadPlaneCount ?? 1} 平面 · 总像素 {matrixData.payloadPixelCount}
                                        </Tag>
                                        <Tag color="purple">
                                            payload [{matrixData.payloadStart}, {matrixData.payloadEnd}) /{" "}
                                            {matrixData.payloadPixelCount}
                                        </Tag>
                                        <Tag color="geekblue">
                                            {matrixData.pixelFormat} · 容器 {matrixData.storageBitDepth} bit · 有效{" "}
                                            {matrixData.effectiveBitDepth} bit · 文件序 {matrixData.rawFileByteOrder}
                                        </Tag>
                                        <Tag color="magenta">16位字节</Tag>
                                        <Tag color="orange">
                                            min/max/mean = {matrixData.pixelMin} / {matrixData.pixelMax} /{" "}
                                            {matrixData.pixelMean.toFixed(2)}
                                        </Tag>
                                    </div>
                                    <Text className="block text-xs text-slate-500">
                                        {(matrixData.payloadPlaneCount ?? 1) > 1
                                            ? `每一行对应 FPGA 原始 payload 的一个平面内 y 行；文本总行数为 ${matrixData.height * (matrixData.payloadPlaneCount ?? 1)} 行，先 ${resolvePayloadPlaneName(frame, 0)} 后 ${resolvePayloadPlaneName(frame, 1)}。`
                                            : "每一行对应 FPGA 原始 payload 的一个 y 行；"}
                                        相邻像素用两个空格分隔；每个像素内部是两个字节，按 16 位字值高字节在前显示，例如 00 A0。
                                    </Text>
                                    <Input.TextArea
                                        ref={payloadMatrixTextAreaRef}
                                        value={matrixData.hexRows.join("\n")}
                                        readOnly
                                        className="font-mono text-xs"
                                        autoSize={false}
                                        style={{ height: 520, whiteSpace: "pre" }}
                                    />
                                </>
                            )}
                        </div>
                    )}
                </div>
            </Modal>
        </>
    );
};

export default FpgaPayloadDataViewer;
