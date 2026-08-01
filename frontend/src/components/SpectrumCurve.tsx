import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Button, Modal, Slider, Tag, Typography } from "antd";

import type { SpectrumExtractionRecord, SpectrumPoint } from "../types/jni";

const { Text } = Typography;

type SpectrumRange = {
    start: number;
    end: number;
};

type HoverState = {
    point: SpectrumPoint;
    x: number;
    y: number;
} | null;

type DragState = {
    startClientX: number;
    range: SpectrumRange;
};

const CHART_PADDING = {
    top: 24,
    right: 22,
    bottom: 44,
    left: 66,
};

const clamp = (value: number, min: number, max: number): number =>
    Math.max(min, Math.min(max, value));

const formatAxisNumber = (value: number): string => {
    if (!Number.isFinite(value)) {
        return "-";
    }
    if (Math.abs(value) >= 1000) {
        return value.toFixed(0);
    }
    if (Math.abs(value) >= 10) {
        return value.toFixed(1);
    }
    return value.toFixed(2);
};

const buildDomain = (points: SpectrumPoint[]): SpectrumRange => {
    if (points.length === 0) {
        return { start: 0, end: 1 };
    }
    let start = points[0].pixelIndex;
    let end = points[0].pixelIndex;
    points.forEach((point) => {
        start = Math.min(start, point.pixelIndex);
        end = Math.max(end, point.pixelIndex);
    });
    return end <= start ? { start, end: start + 1 } : { start, end };
};

const normalizeRange = (range: SpectrumRange, domain: SpectrumRange): SpectrumRange => {
    const domainSpan = Math.max(domain.end - domain.start, 1);
    const span = clamp(range.end - range.start, 1, domainSpan);
    let start = clamp(range.start, domain.start, domain.end - span);
    let end = start + span;
    if (end > domain.end) {
        end = domain.end;
        start = end - span;
    }
    return { start, end };
};

const visiblePointsForRange = (points: SpectrumPoint[], range: SpectrumRange): SpectrumPoint[] => {
    if (points.length < 2) {
        return points;
    }
    const firstInside = points.findIndex((point) => point.pixelIndex >= range.start);
    if (firstInside < 0) {
        return points.slice(-2);
    }
    const startIndex = Math.max(0, firstInside - 1);
    const firstAfter = points.findIndex((point) => point.pixelIndex > range.end);
    const endIndex = firstAfter < 0 ? points.length - 1 : Math.min(points.length - 1, firstAfter);
    return points.slice(startIndex, endIndex + 1);
};

const buildIntensityExtent = (points: SpectrumPoint[]): SpectrumRange => {
    if (points.length === 0) {
        return { start: 0, end: 1 };
    }
    let min = points[0].intensity;
    let max = points[0].intensity;
    points.forEach((point) => {
        min = Math.min(min, point.intensity);
        max = Math.max(max, point.intensity);
    });
    if (max <= min) {
        return { start: min - 0.5, end: max + 0.5 };
    }
    const padding = (max - min) * 0.08;
    return { start: min - padding, end: max + padding };
};

const findNearestPoint = (points: SpectrumPoint[], targetPixelIndex: number): SpectrumPoint | null => {
    if (points.length === 0) {
        return null;
    }
    let left = 0;
    let right = points.length - 1;
    while (left < right) {
        const middle = Math.floor((left + right) / 2);
        if (points[middle].pixelIndex < targetPixelIndex) {
            left = middle + 1;
        } else {
            right = middle;
        }
    }
    const candidate = points[left];
    const previous = left > 0 ? points[left - 1] : candidate;
    return Math.abs(previous.pixelIndex - targetPixelIndex) < Math.abs(candidate.pixelIndex - targetPixelIndex)
        ? previous
        : candidate;
};

const pointToCanvasPosition = (
    point: SpectrumPoint,
    xRange: SpectrumRange,
    yRange: SpectrumRange,
    width: number,
    height: number,
): { x: number; y: number } => {
    const plotWidth = Math.max(width - CHART_PADDING.left - CHART_PADDING.right, 1);
    const plotHeight = Math.max(height - CHART_PADDING.top - CHART_PADDING.bottom, 1);
    const xSpan = Math.max(xRange.end - xRange.start, 1);
    const ySpan = Math.max(yRange.end - yRange.start, 1);
    return {
        x: CHART_PADDING.left + ((point.pixelIndex - xRange.start) / xSpan) * plotWidth,
        y: CHART_PADDING.top + (1 - (point.intensity - yRange.start) / ySpan) * plotHeight,
    };
};

const drawSpectrumCanvas = (
    canvas: HTMLCanvasElement,
    spectrum: SpectrumExtractionRecord,
    range: SpectrumRange,
    hoverPoint: SpectrumPoint | null,
) => {
    const parent = canvas.parentElement;
    if (!parent) {
        return;
    }
    const rect = parent.getBoundingClientRect();
    const width = Math.max(rect.width, 320);
    const height = Math.max(rect.height, 220);
    const devicePixelRatio = window.devicePixelRatio || 1;
    canvas.width = Math.floor(width * devicePixelRatio);
    canvas.height = Math.floor(height * devicePixelRatio);
    canvas.style.width = `${width}px`;
    canvas.style.height = `${height}px`;

    const context = canvas.getContext("2d");
    if (!context) {
        return;
    }
    context.setTransform(devicePixelRatio, 0, 0, devicePixelRatio, 0, 0);
    context.clearRect(0, 0, width, height);

    const plotWidth = width - CHART_PADDING.left - CHART_PADDING.right;
    const plotHeight = height - CHART_PADDING.top - CHART_PADDING.bottom;
    const visiblePoints = visiblePointsForRange(spectrum.points, range);
    const yRange = buildIntensityExtent(visiblePoints);
    const xSpan = Math.max(range.end - range.start, 1);
    const ySpan = Math.max(yRange.end - yRange.start, 1);

    context.fillStyle = "#020617";
    context.fillRect(0, 0, width, height);

    context.font = "12px ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI'";
    context.lineWidth = 1;
    context.strokeStyle = "#1e293b";
    context.fillStyle = "#94a3b8";

    const yTickCount = 5;
    for (let index = 0; index <= yTickCount; index++) {
        const ratio = index / yTickCount;
        const y = CHART_PADDING.top + ratio * plotHeight;
        const value = yRange.end - ratio * ySpan;
        context.beginPath();
        context.moveTo(CHART_PADDING.left, y);
        context.lineTo(width - CHART_PADDING.right, y);
        context.stroke();
        context.textAlign = "right";
        context.textBaseline = "middle";
        context.fillText(formatAxisNumber(value), CHART_PADDING.left - 10, y);
    }

    const xTickCount = 6;
    for (let index = 0; index <= xTickCount; index++) {
        const ratio = index / xTickCount;
        const x = CHART_PADDING.left + ratio * plotWidth;
        const value = range.start + ratio * xSpan;
        context.beginPath();
        context.moveTo(x, CHART_PADDING.top);
        context.lineTo(x, height - CHART_PADDING.bottom);
        context.stroke();
        context.textAlign = "center";
        context.textBaseline = "top";
        context.fillText(formatAxisNumber(value), x, height - CHART_PADDING.bottom + 10);
    }

    context.strokeStyle = "#475569";
    context.beginPath();
    context.moveTo(CHART_PADDING.left, CHART_PADDING.top);
    context.lineTo(CHART_PADDING.left, height - CHART_PADDING.bottom);
    context.lineTo(width - CHART_PADDING.right, height - CHART_PADDING.bottom);
    context.stroke();

    context.fillStyle = "#cbd5e1";
    context.textAlign = "left";
    context.textBaseline = "top";
    context.fillText("intensity", CHART_PADDING.left, 6);
    context.textAlign = "right";
    context.fillText("pixelIndex", width - CHART_PADDING.right, height - 18);

    if (visiblePoints.length >= 2) {
        context.save();
        context.beginPath();
        context.rect(CHART_PADDING.left, CHART_PADDING.top, plotWidth, plotHeight);
        context.clip();

        context.strokeStyle = "#38bdf8";
        context.lineWidth = 1.8;
        context.shadowColor = "rgba(56, 189, 248, 0.35)";
        context.shadowBlur = 8;
        context.beginPath();

        const maxRenderPoints = Math.max(1500, Math.floor(plotWidth * 2));
        const step = Math.max(1, Math.floor(visiblePoints.length / maxRenderPoints));
        let hasMoved = false;
        for (let index = 0; index < visiblePoints.length; index += step) {
            const point = visiblePoints[index];
            const position = pointToCanvasPosition(point, range, yRange, width, height);
            if (!hasMoved) {
                context.moveTo(position.x, position.y);
                hasMoved = true;
            } else {
                context.lineTo(position.x, position.y);
            }
        }
        const lastPoint = visiblePoints[visiblePoints.length - 1];
        const lastPosition = pointToCanvasPosition(lastPoint, range, yRange, width, height);
        context.lineTo(lastPosition.x, lastPosition.y);
        context.stroke();
        context.restore();
    }

    if (hoverPoint && hoverPoint.pixelIndex >= range.start && hoverPoint.pixelIndex <= range.end) {
        const position = pointToCanvasPosition(hoverPoint, range, yRange, width, height);
        context.save();
        context.strokeStyle = "rgba(251, 191, 36, 0.75)";
        context.lineWidth = 1;
        context.setLineDash([4, 4]);
        context.beginPath();
        context.moveTo(position.x, CHART_PADDING.top);
        context.lineTo(position.x, height - CHART_PADDING.bottom);
        context.moveTo(CHART_PADDING.left, position.y);
        context.lineTo(width - CHART_PADDING.right, position.y);
        context.stroke();
        context.setLineDash([]);
        context.fillStyle = "#fbbf24";
        context.beginPath();
        context.arc(position.x, position.y, 3.5, 0, Math.PI * 2);
        context.fill();
        context.restore();
    }
};

const SpectrumCanvas: React.FC<{
    spectrum: SpectrumExtractionRecord;
    range: SpectrumRange;
    hoverPoint?: SpectrumPoint | null;
    className?: string;
}> = ({ spectrum, range, hoverPoint = null, className = "h-56" }) => {
    const canvasRef = useRef<HTMLCanvasElement | null>(null);

    const redraw = useCallback(() => {
        if (!canvasRef.current) {
            return;
        }
        drawSpectrumCanvas(canvasRef.current, spectrum, range, hoverPoint);
    }, [hoverPoint, range, spectrum]);

    useEffect(() => {
        redraw();
        const canvas = canvasRef.current;
        const parent = canvas?.parentElement;
        if (!parent) {
            return undefined;
        }
        const resizeObserver = new ResizeObserver(redraw);
        resizeObserver.observe(parent);
        return () => resizeObserver.disconnect();
    }, [redraw]);

    return (
        <div className={`relative w-full overflow-hidden rounded-md bg-slate-950 ${className}`}>
            <canvas ref={canvasRef} className="block h-full w-full" />
        </div>
    );
};

const SpectrumCurve: React.FC<{ spectrum: SpectrumExtractionRecord }> = ({ spectrum }) => {
    const [previewOpen, setPreviewOpen] = useState(false);
    const [viewRange, setViewRange] = useState<SpectrumRange | null>(null);
    const [dragState, setDragState] = useState<DragState | null>(null);
    const [hover, setHover] = useState<HoverState>(null);
    const chartRef = useRef<HTMLDivElement | null>(null);

    const domain = useMemo(() => buildDomain(spectrum.points), [spectrum.points]);
    const effectiveRange = normalizeRange(viewRange ?? domain, domain);
    const domainSpan = Math.max(domain.end - domain.start, 1);
    const viewSpan = Math.max(effectiveRange.end - effectiveRange.start, 1);
    const zoom = Math.max(1, domainSpan / viewSpan);
    const minSpan = Math.min(domainSpan, Math.max(2, domainSpan / 180));
    const canPan = viewSpan < domainSpan - 0.001;

    useEffect(() => {
        setViewRange(null);
        setDragState(null);
        setHover(null);
    }, [spectrum.id]);

    const updateRange = (nextRange: SpectrumRange) => {
        setViewRange(normalizeRange(nextRange, domain));
    };

    const cursorToPixelIndex = (clientX: number, rect: DOMRect): number => {
        const plotWidth = Math.max(rect.width - CHART_PADDING.left - CHART_PADDING.right, 1);
        const cursorRatio = clamp((clientX - rect.left - CHART_PADDING.left) / plotWidth, 0, 1);
        return effectiveRange.start + cursorRatio * viewSpan;
    };

    const updateHover = (clientX: number, rect: DOMRect) => {
        const targetPixelIndex = cursorToPixelIndex(clientX, rect);
        const point = findNearestPoint(spectrum.points, targetPixelIndex);
        if (!point) {
            setHover(null);
            return;
        }
        const visiblePoints = visiblePointsForRange(spectrum.points, effectiveRange);
        const yRange = buildIntensityExtent(visiblePoints);
        const position = pointToCanvasPosition(point, effectiveRange, yRange, rect.width, rect.height);
        setHover({
            point,
            x: clamp(position.x, CHART_PADDING.left, rect.width - CHART_PADDING.right),
            y: clamp(position.y, CHART_PADDING.top, rect.height - CHART_PADDING.bottom),
        });
    };

    const handleWheel = (event: React.WheelEvent<HTMLDivElement>) => {
        event.preventDefault();
        const rect = event.currentTarget.getBoundingClientRect();

        if (event.shiftKey || Math.abs(event.deltaX) > Math.abs(event.deltaY)) {
            const plotWidth = Math.max(rect.width - CHART_PADDING.left - CHART_PADDING.right, 1);
            const panDelta = ((event.deltaX || event.deltaY) / plotWidth) * viewSpan;
            updateRange({
                start: effectiveRange.start + panDelta,
                end: effectiveRange.end + panDelta,
            });
            updateHover(event.clientX, rect);
            return;
        }

        const plotWidth = Math.max(rect.width - CHART_PADDING.left - CHART_PADDING.right, 1);
        const cursorRatio = clamp((event.clientX - rect.left - CHART_PADDING.left) / plotWidth, 0, 1);
        const focalPixelIndex = effectiveRange.start + cursorRatio * viewSpan;
        const zoomFactor = Math.exp(event.deltaY * 0.0014);
        const nextSpan = clamp(viewSpan * zoomFactor, minSpan, domainSpan);
        updateRange({
            start: focalPixelIndex - cursorRatio * nextSpan,
            end: focalPixelIndex + (1 - cursorRatio) * nextSpan,
        });
        updateHover(event.clientX, rect);
    };

    const handleMouseDown = (event: React.MouseEvent<HTMLDivElement>) => {
        if (!canPan) {
            return;
        }
        event.preventDefault();
        setDragState({
            startClientX: event.clientX,
            range: effectiveRange,
        });
    };

    const handleMouseMove = (event: React.MouseEvent<HTMLDivElement>) => {
        if (!chartRef.current) {
            return;
        }
        const rect = chartRef.current.getBoundingClientRect();
        if (dragState) {
            const plotWidth = Math.max(rect.width - CHART_PADDING.left - CHART_PADDING.right, 1);
            const deltaRatio = (event.clientX - dragState.startClientX) / plotWidth;
            const deltaPixelIndex = -deltaRatio * (dragState.range.end - dragState.range.start);
            updateRange({
                start: dragState.range.start + deltaPixelIndex,
                end: dragState.range.end + deltaPixelIndex,
            });
        }
        updateHover(event.clientX, rect);
    };

    const stopInteraction = () => {
        setDragState(null);
    };

    const handleSliderChange = (value: number[]) => {
        if (value.length !== 2) {
            return;
        }
        updateRange({
            start: value[0],
            end: value[1],
        });
    };

    const zoomAroundCenter = (factor: number) => {
        const center = (effectiveRange.start + effectiveRange.end) / 2;
        const nextSpan = clamp(viewSpan * factor, minSpan, domainSpan);
        updateRange({
            start: center - nextSpan / 2,
            end: center + nextSpan / 2,
        });
    };

    const resetView = () => {
        setViewRange(null);
        setDragState(null);
        setHover(null);
    };

    return (
        <div className="rounded-md bg-slate-950 p-2">
            <button
                type="button"
                className="block w-full cursor-zoom-in rounded border-0 bg-transparent p-0 text-left"
                title="点击放大查看一维光谱"
                onClick={() => setPreviewOpen(true)}
            >
                <SpectrumCanvas spectrum={spectrum} range={domain} />
            </button>
            <div className="mt-1 text-right text-xs text-slate-500">点击曲线放大查看</div>
            <Modal
                open={previewOpen}
                title="一维光谱放大预览"
                footer={null}
                width="90vw"
                centered
                destroyOnClose
                onCancel={() => setPreviewOpen(false)}
            >
                <div className="space-y-3">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                        <Text className="text-xs text-slate-500">
                            鼠标放在关注位置滚轮缩放；按住曲线左右拖动；Shift+滚轮或触控板横向滚动可平移。
                        </Text>
                        <div className="flex flex-wrap items-center gap-2">
                            <Tag color="blue" className="m-0">
                                缩放 {zoom.toFixed(1)}x
                            </Tag>
                            <Tag color="cyan" className="m-0">
                                pixelIndex {Math.round(effectiveRange.start)} ~ {Math.round(effectiveRange.end)}
                            </Tag>
                            <Button size="small" onClick={() => zoomAroundCenter(0.72)}>
                                放大
                            </Button>
                            <Button size="small" onClick={() => zoomAroundCenter(1.38)} disabled={zoom <= 1.001}>
                                缩小
                            </Button>
                            <Button size="small" onClick={resetView} disabled={zoom <= 1.001}>
                                复位
                            </Button>
                        </div>
                    </div>

                    <div
                        ref={chartRef}
                        className={`relative rounded-lg border border-slate-800 bg-slate-950 p-0 ${
                            canPan ? (dragState ? "cursor-grabbing" : "cursor-grab") : "cursor-zoom-in"
                        }`}
                        title="滚轮以鼠标位置为中心缩放；拖动可左右平移"
                        onWheel={handleWheel}
                        onMouseDown={handleMouseDown}
                        onMouseMove={handleMouseMove}
                        onMouseUp={stopInteraction}
                        onMouseLeave={() => {
                            stopInteraction();
                            setHover(null);
                        }}
                    >
                        <SpectrumCanvas
                            spectrum={spectrum}
                            range={effectiveRange}
                            hoverPoint={hover?.point ?? null}
                            className="h-[68vh] max-h-[680px] min-h-[420px] select-none"
                        />
                        {hover && (
                            <div
                                className="pointer-events-none absolute z-10 rounded-md border border-amber-300/60 bg-slate-900/95 px-2.5 py-1.5 text-xs text-slate-100 shadow-lg"
                                style={{
                                    left: clamp(hover.x + 12, 76, Math.max((chartRef.current?.clientWidth ?? 420) - 170, 76)),
                                    top: clamp(hover.y - 36, 10, Math.max((chartRef.current?.clientHeight ?? 300) - 60, 10)),
                                }}
                            >
                                <div>pixelIndex: {hover.point.pixelIndex}</div>
                                <div>intensity: {formatAxisNumber(hover.point.intensity)}</div>
                            </div>
                        )}
                    </div>

                    <div className="rounded-lg border border-slate-200 bg-slate-50 px-4 py-3">
                        <div className="mb-1 flex justify-between text-xs text-slate-500">
                            <span>{Math.round(domain.start)}</span>
                            <span>拖动两端改变查看范围，拖动中间区域平移</span>
                            <span>{Math.round(domain.end)}</span>
                        </div>
                        <Slider
                            range
                            min={domain.start}
                            max={domain.end}
                            step={1}
                            value={[effectiveRange.start, effectiveRange.end]}
                            onChange={(value) => handleSliderChange(value as number[])}
                            tooltip={{ formatter: (value) => (typeof value === "number" ? Math.round(value) : "") }}
                        />
                    </div>
                </div>
            </Modal>
        </div>
    );
};

export default SpectrumCurve;
