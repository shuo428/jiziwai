import React, { useEffect, useMemo, useState } from "react";
import { Button, Empty, Modal, Space, Tag, Typography } from "antd";

import type { ImageFrameRecord } from "../types/jni";

const { Text } = Typography;

export type ImagePreviewVersionKey = "raw" | "calibrated" | "processed";

type PreviewVersion = {
    key: ImagePreviewVersionKey;
    label: string;
    url: string;
    tagColor: string;
    description: string;
};

interface ImageVersionPreviewProps {
    frame: ImageFrameRecord | null;
    defaultVersion?: ImagePreviewVersionKey;
    emptyText?: string;
    imageAreaClassName?: string;
    rawLabel?: string;
    rawDescription?: string;
}

const clampZoom = (value: number): number => Math.max(0.5, Math.min(6, value));

/**
 * 单帧多版本图像预览组件。
 *
 * raw / calibrated / processed 不再并排硬塞在同一个小区域里，而是一次只展示一个大图；
 * 研究人员可以通过版本按钮切换，也可以打开放大弹窗后用滚轮或按钮缩放观察细节。
 */
const ImageVersionPreview: React.FC<ImageVersionPreviewProps> = ({
    frame,
    defaultVersion = "raw",
    emptyText = "暂无图像帧",
    imageAreaClassName = "min-h-[340px]",
    rawLabel = "原图",
    rawDescription = "接收后保存的原始预览图，对应 raw16le.bin。",
}) => {
    const versions = useMemo<PreviewVersion[]>(() => {
        if (!frame) {
            return [];
        }
        return [
            {
                key: "raw",
                label: rawLabel,
                url: frame.imageDataUrl,
                tagColor: "default",
                description: rawDescription,
            },
            {
                key: "calibrated",
                label: "校准后",
                url: frame.calibratedImageDataUrl,
                tagColor: "cyan",
                description: "暗场扣除、平场校正和稳定缺陷地图修复后的图像。",
            },
            {
                key: "processed",
                label: "处理后",
                url: frame.processedImageDataUrl,
                tagColor: "green",
                description: "质量处置策略进一步修复后的图像。",
            },
        ].filter((item) => Boolean(item.url));
    }, [frame, rawDescription, rawLabel]);

    const [activeKey, setActiveKey] = useState<ImagePreviewVersionKey>(defaultVersion);
    const [zoomVisible, setZoomVisible] = useState(false);
    const [zoomScale, setZoomScale] = useState(1);

    useEffect(() => {
        if (versions.length === 0) {
            setActiveKey(defaultVersion);
            return;
        }
        const preferred = versions.find((item) => item.key === defaultVersion);
        setActiveKey(preferred?.key ?? versions[0].key);
    }, [defaultVersion, frame?.id, versions]);

    const activeVersion = versions.find((item) => item.key === activeKey) ?? versions[0];

    const openZoom = () => {
        setZoomScale(1);
        setZoomVisible(true);
    };

    return (
        <>
            <div className="overflow-hidden rounded-lg border border-slate-200 bg-white shadow-inner">
                <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-200 bg-slate-50 px-3 py-2">
                    <Space wrap size={[6, 6]}>
                        {versions.map((version) => (
                            <Button
                                key={version.key}
                                size="small"
                                type={version.key === activeVersion?.key ? "primary" : "default"}
                                onClick={() => setActiveKey(version.key)}
                            >
                                {version.label}
                            </Button>
                        ))}
                    </Space>
                    <Space wrap size={[6, 6]}>
                        {activeVersion && (
                            <Tag color={activeVersion.tagColor} className="m-0">
                                当前：{activeVersion.label}
                            </Tag>
                        )}
                        <Button size="small" disabled={!activeVersion} onClick={openZoom}>
                            放大查看
                        </Button>
                    </Space>
                </div>

                {activeVersion ? (
                    <>
                        <div className={`flex items-center justify-center bg-slate-950 p-3 ${imageAreaClassName}`}>
                            <img
                                src={activeVersion.url}
                                alt={`${activeVersion.label}光谱图像`}
                                className="max-h-full max-w-full object-contain"
                                onDoubleClick={openZoom}
                            />
                        </div>
                        <div className="border-t border-slate-200 bg-white px-3 py-2">
                            <Text className="text-xs text-slate-500">{activeVersion.description}</Text>
                        </div>
                    </>
                ) : (
                    <div className={`flex items-center justify-center bg-slate-950 p-3 ${imageAreaClassName}`}>
                        <Empty
                            image={Empty.PRESENTED_IMAGE_SIMPLE}
                            description={<span className="text-slate-400">{emptyText}</span>}
                        />
                    </div>
                )}
            </div>

            <Modal
                title={activeVersion ? `放大查看：${activeVersion.label}` : "放大查看"}
                open={zoomVisible}
                onCancel={() => setZoomVisible(false)}
                footer={[
                    <Button key="zoomOut" onClick={() => setZoomScale((value) => clampZoom(value / 1.25))}>
                        缩小
                    </Button>,
                    <Button key="reset" onClick={() => setZoomScale(1)}>
                        100%
                    </Button>,
                    <Button key="zoomIn" onClick={() => setZoomScale((value) => clampZoom(value * 1.25))}>
                        放大
                    </Button>,
                    <Button key="close" type="primary" onClick={() => setZoomVisible(false)}>
                        关闭
                    </Button>,
                ]}
                width="96vw"
                style={{ maxWidth: 1500, top: 22 }}
                destroyOnClose
            >
                <div className="space-y-3">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                        <Space wrap size={[6, 6]}>
                            {versions.map((version) => (
                                <Button
                                    key={version.key}
                                    size="small"
                                    type={version.key === activeVersion?.key ? "primary" : "default"}
                                    onClick={() => {
                                        setActiveKey(version.key);
                                        setZoomScale(1);
                                    }}
                                >
                                    {version.label}
                                </Button>
                            ))}
                        </Space>
                        <Tag color="purple" className="m-0">
                            缩放 {(zoomScale * 100).toFixed(0)}%
                        </Tag>
                    </div>

                    <div
                        className="h-[76vh] overflow-auto rounded-lg bg-slate-950 p-4 text-center"
                        onWheel={(event) => {
                            event.preventDefault();
                            const factor = event.deltaY < 0 ? 1.12 : 0.88;
                            setZoomScale((value) => clampZoom(value * factor));
                        }}
                    >
                        {activeVersion && (
                            <img
                                src={activeVersion.url}
                                alt={`${activeVersion.label}光谱图像放大预览`}
                                className="inline-block select-none object-contain align-middle"
                                style={{
                                    width: `${zoomScale * 100}%`,
                                    maxWidth: zoomScale <= 1 ? "100%" : "none",
                                }}
                                draggable={false}
                            />
                        )}
                    </div>

                    <Text className="block text-xs text-slate-500">
                        提示：在图像区域滚轮缩放；放大后可用滚动条查看局部细节。双击主预览图也可以打开此窗口。
                    </Text>
                </div>
            </Modal>
        </>
    );
};

export default ImageVersionPreview;
