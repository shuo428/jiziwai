import { jniApi } from "./jniService";
import { useJNIStore } from "../store/jniStore";
import type {
    CalibrationRequest,
    CalibrationGlobalSettingsRecord,
    CalibrationGlobalSettingsRequest,
    CalibrationPreviewRecord,
    CalibrationSessionRecord,
    BridgeConnectionForm,
    BridgeConnectionState,
    ConfigAckRecord,
    ImageFrameRecord,
    ImagePixelDataRecord,
    ImagePixelDataRequest,
    MultiFrameAnalysisRecord,
    MultiFrameAnalysisRequest,
    JniEventEnvelope,
    QualityRecommendedAction,
    SpectrumExtractionRecord,
    SpectrumExtractionRequest,
    SpectrumPoint,
    SpectrumRoi,
    StatusRecord,
    TriggerCaptureOptions,
    TransportErrorRecord,
} from "../types/jni";

type PendingRequest<T> = {
    timeoutId: number;
    resolve: (value: T) => void;
    reject: (error: Error) => void;
};

let bridgeWebSocket: WebSocket | null = null;
let websocketPromise: Promise<void> | null = null;
let pendingFrame: PendingRequest<ImageFrameRecord> | null = null;
let pendingStatus: PendingRequest<StatusRecord> | null = null;
let pendingConfigAck: PendingRequest<ConfigAckRecord> | null = null;

const buildId = (prefix: string): string => `${prefix}_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;

const normalizeState = (state: Partial<BridgeConnectionState>): Partial<BridgeConnectionState> => ({
    host: typeof state.host === "string" ? state.host : "",
    controlPort: typeof state.controlPort === "number" ? state.controlPort : 0,
    imagePort: typeof state.imagePort === "number" ? state.imagePort : 0,
    verifyCrc: typeof state.verifyCrc === "boolean" ? state.verifyCrc : true,
    connected: Boolean(state.connected),
    lastError: state.lastError ?? null,
    message: state.message ?? null,
    fullConfigSize: typeof state.fullConfigSize === "number" ? state.fullConfigSize : 512,
});

const getWebSocketUrl = (): string => {
    const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
    return `${protocol}//${window.location.host}/ws`;
};

const clearPendingRequest = <T>(pending: PendingRequest<T> | null): void => {
    if (!pending) {
        return;
    }
    window.clearTimeout(pending.timeoutId);
};

const rejectPendingRequest = <T>(pending: PendingRequest<T> | null, message: string): null => {
    if (!pending) {
        return null;
    }
    clearPendingRequest(pending);
    pending.reject(new Error(message));
    return null;
};

const ensureConnected = (): void => {
    const { bridgeState } = useJNIStore.getState();
    if (!bridgeState.connected) {
        throw new Error("设备尚未连接，请先填写 host、controlPort、imagePort 并建立连接");
    }
};

const applyConnectionState = (state: Partial<BridgeConnectionState>): void => {
    const store = useJNIStore.getState();
    store.actions.hydrateBridgeState(normalizeState(state));
    if (state.lastError) {
        store.actions.setError(state.lastError);
    } else if (state.connected) {
        store.actions.setError(null);
    }
};

const parseRecommendedActions = (value: unknown): QualityRecommendedAction[] => {
    if (!Array.isArray(value)) {
        return [];
    }
    return value
        .filter((item): item is Record<string, unknown> => Boolean(item) && typeof item === "object")
        .map((item) => ({
            code: typeof item.code === "string" ? item.code : "UNKNOWN_ACTION",
            label: typeof item.label === "string" ? item.label : "未知建议",
            stage: typeof item.stage === "string" ? item.stage : "REVIEW",
            severity: typeof item.severity === "string" ? item.severity : "INFO",
            reason: typeof item.reason === "string" ? item.reason : "",
            repairable: typeof item.repairable === "boolean" ? item.repairable : false,
        }));
};

const parseStringArray = (value: unknown): string[] => {
    if (!Array.isArray(value)) {
        return [];
    }
    return value.filter((item): item is string => typeof item === "string");
};

const parseRecordArray = (value: unknown): Array<Record<string, unknown>> => {
    if (!Array.isArray(value)) {
        return [];
    }
    return value.filter((item): item is Record<string, unknown> => Boolean(item) && typeof item === "object");
};

const normalizeImageFrame = (timestamp: string, payload: any): ImageFrameRecord => ({
        id: Number(payload?.id ?? 0),
        captureId: Number(payload?.captureId ?? 0),
        requestId: typeof payload?.requestId === "string" ? payload.requestId : "",
        timestamp: typeof payload?.timestamp === "string" ? payload.timestamp : timestamp,
        width: Number(payload?.width ?? 0),
        height: Number(payload?.height ?? 0),
        raw8Length: Number(payload?.raw8Length ?? 0),
        raw16Length: Number(payload?.raw16Length ?? 0),
        payloadLength: Number(payload?.payloadLength ?? 0),
        pixelFormat: typeof payload?.pixelFormat === "string" ? payload.pixelFormat : "RAW16_LOW12",
        imageDataUrl: typeof payload?.imageDataUrl === "string" ? payload.imageDataUrl : "",
        integrityPassed: typeof payload?.integrityPassed === "boolean" ? payload.integrityPassed : null,
        integrityResultCode:
            typeof payload?.integrityResultCode === "string" ? payload.integrityResultCode : null,
        qualityStatus: typeof payload?.qualityStatus === "string" ? payload.qualityStatus : null,
        pixelMin: typeof payload?.pixelMin === "number" ? payload.pixelMin : null,
        pixelMax: typeof payload?.pixelMax === "number" ? payload.pixelMax : null,
        pixelMean: typeof payload?.pixelMean === "number" ? payload.pixelMean : null,
        pixelStddev: typeof payload?.pixelStddev === "number" ? payload.pixelStddev : null,
        blackPixelRatio: typeof payload?.blackPixelRatio === "number" ? payload.blackPixelRatio : null,
        saturationPixelRatio:
            typeof payload?.saturationPixelRatio === "number" ? payload.saturationPixelRatio : null,
        abnormalRowCount: typeof payload?.abnormalRowCount === "number" ? payload.abnormalRowCount : null,
        abnormalColumnCount:
            typeof payload?.abnormalColumnCount === "number" ? payload.abnormalColumnCount : null,
        badPixelCount: typeof payload?.badPixelCount === "number" ? payload.badPixelCount : null,
        qualitySummaryMessage:
            typeof payload?.qualitySummaryMessage === "string" ? payload.qualitySummaryMessage : null,
        qualityDetails:
            payload?.qualityDetails && typeof payload.qualityDetails === "object" ? payload.qualityDetails : null,
        rawHardQualitySnapshot:
            payload?.rawHardQualitySnapshot && typeof payload.rawHardQualitySnapshot === "object"
                ? payload.rawHardQualitySnapshot
                : null,
        calibratedQualitySnapshot:
            payload?.calibratedQualitySnapshot && typeof payload.calibratedQualitySnapshot === "object"
                ? payload.calibratedQualitySnapshot
                : null,
        originalQualitySnapshot:
            payload?.originalQualitySnapshot && typeof payload.originalQualitySnapshot === "object"
                ? payload.originalQualitySnapshot
                : null,
        dispositionStatus: typeof payload?.dispositionStatus === "string" ? payload.dispositionStatus : null,
        usableForSpectral: typeof payload?.usableForSpectral === "boolean" ? payload.usableForSpectral : null,
        dispositionMessage: typeof payload?.dispositionMessage === "string" ? payload.dispositionMessage : null,
        recommendedActions: parseRecommendedActions(payload?.recommendedActions),
        dispositionReasonCodes: parseStringArray(payload?.dispositionReasonCodes),
        processedImageDataUrl: typeof payload?.processedImageDataUrl === "string" ? payload.processedImageDataUrl : "",
        processingStatus: typeof payload?.processingStatus === "string" ? payload.processingStatus : null,
        processingMessage: typeof payload?.processingMessage === "string" ? payload.processingMessage : null,
        executedProcessingActions: parseRecordArray(payload?.executedProcessingActions),
        processedQualityStatus:
            typeof payload?.processedQualityStatus === "string" ? payload.processedQualityStatus : null,
        processedPixelMin: typeof payload?.processedPixelMin === "number" ? payload.processedPixelMin : null,
        processedPixelMax: typeof payload?.processedPixelMax === "number" ? payload.processedPixelMax : null,
        processedPixelMean: typeof payload?.processedPixelMean === "number" ? payload.processedPixelMean : null,
        processedPixelStddev:
            typeof payload?.processedPixelStddev === "number" ? payload.processedPixelStddev : null,
        processedBlackPixelRatio:
            typeof payload?.processedBlackPixelRatio === "number" ? payload.processedBlackPixelRatio : null,
        processedSaturationPixelRatio:
            typeof payload?.processedSaturationPixelRatio === "number" ? payload.processedSaturationPixelRatio : null,
        processedAbnormalRowCount:
            typeof payload?.processedAbnormalRowCount === "number" ? payload.processedAbnormalRowCount : null,
        processedAbnormalColumnCount:
            typeof payload?.processedAbnormalColumnCount === "number" ? payload.processedAbnormalColumnCount : null,
        processedBadPixelCount:
            typeof payload?.processedBadPixelCount === "number" ? payload.processedBadPixelCount : null,
        processedQualitySummaryMessage:
            typeof payload?.processedQualitySummaryMessage === "string" ? payload.processedQualitySummaryMessage : null,
        processedQualityDetails:
            payload?.processedQualityDetails && typeof payload.processedQualityDetails === "object"
                ? payload.processedQualityDetails
                : null,
        processedQualitySnapshot:
            payload?.processedQualitySnapshot && typeof payload.processedQualitySnapshot === "object"
                ? payload.processedQualitySnapshot
                : null,
        processedDispositionStatus:
            typeof payload?.processedDispositionStatus === "string" ? payload.processedDispositionStatus : null,
        processedUsableForSpectral:
            typeof payload?.processedUsableForSpectral === "boolean" ? payload.processedUsableForSpectral : null,
        processedDispositionMessage:
            typeof payload?.processedDispositionMessage === "string" ? payload.processedDispositionMessage : null,
});

const normalizePixelRows = (value: unknown): number[][] => {
    if (!Array.isArray(value)) {
        return [];
    }
    return value.map((row) => (
        Array.isArray(row)
            ? row.map((pixel) => Number(pixel ?? 0))
            : []
    ));
};

const normalizeStringRows = (value: unknown): string[] => {
    if (!Array.isArray(value)) {
        return [];
    }
    return value.map((row) => (typeof row === "string" ? row : String(row ?? "")));
};

const normalizeImagePixelData = (payload: any): ImagePixelDataRecord => ({
    imageId: Number(payload?.imageId ?? 0),
    sourceMode: typeof payload?.sourceMode === "string" ? payload.sourceMode : "ORIGINAL",
    width: Number(payload?.width ?? 0),
    height: Number(payload?.height ?? 0),
    xStart: Number(payload?.xStart ?? 0),
    yStart: Number(payload?.yStart ?? 0),
    xEnd: Number(payload?.xEnd ?? 0),
    yEnd: Number(payload?.yEnd ?? 0),
    roiWidth: Number(payload?.roiWidth ?? 0),
    roiHeight: Number(payload?.roiHeight ?? 0),
    storageBitDepth: Number(payload?.storageBitDepth ?? 16),
    effectiveBitDepth: Number(payload?.effectiveBitDepth ?? 12),
    pixelFormat: typeof payload?.pixelFormat === "string" ? payload.pixelFormat : "RAW16_LOW12",
    displayFormat: typeof payload?.displayFormat === "string" ? payload.displayFormat : "DN",
    rawFileByteOrder: typeof payload?.rawFileByteOrder === "string" ? payload.rawFileByteOrder : "LITTLE_ENDIAN",
    fullFrame: Boolean(payload?.fullFrame),
    pixelMin: Number(payload?.pixelMin ?? 0),
    pixelMax: Number(payload?.pixelMax ?? 0),
    pixelMean: Number(payload?.pixelMean ?? 0),
    rows: normalizePixelRows(payload?.rows),
    hexRows: normalizeStringRows(payload?.hexRows),
});

const normalizeRoi = (value: any): SpectrumRoi => ({
    xStart: Number(value?.xStart ?? 0),
    xEnd: Number(value?.xEnd ?? 0),
    yStart: Number(value?.yStart ?? 0),
    yEnd: Number(value?.yEnd ?? 0),
});

const normalizeSpectrumPoints = (value: unknown): SpectrumPoint[] => {
    if (!Array.isArray(value)) {
        return [];
    }
    return value.map((item: any) => ({
        pixelIndex: Number(item?.pixelIndex ?? 0),
        intensity: Number(item?.intensity ?? 0),
    }));
};

const normalizeSpectrumExtraction = (payload: any): SpectrumExtractionRecord => ({
    id: Number(payload?.id ?? 0),
    imageId: Number(payload?.imageId ?? 0),
    captureId: Number(payload?.captureId ?? 0),
    sourceMode: typeof payload?.sourceMode === "string" ? payload.sourceMode : "ORIGINAL",
    sourceQualityStatus: typeof payload?.sourceQualityStatus === "string" ? payload.sourceQualityStatus : "PASS",
    wavelengthAxis: typeof payload?.wavelengthAxis === "string" ? payload.wavelengthAxis : "X",
    roi: normalizeRoi(payload?.roi),
    rectified: Boolean(payload?.rectified),
    maxShiftPixels: Number(payload?.maxShiftPixels ?? 0),
    shiftMin: Number(payload?.shiftMin ?? 0),
    shiftMax: Number(payload?.shiftMax ?? 0),
    shiftMeanAbs: Number(payload?.shiftMeanAbs ?? 0),
    integrationMethod: typeof payload?.integrationMethod === "string" ? payload.integrationMethod : "MEAN",
    pointCount: Number(payload?.pointCount ?? 0),
    intensityMin: Number(payload?.intensityMin ?? 0),
    intensityMax: Number(payload?.intensityMax ?? 0),
    intensityMean: Number(payload?.intensityMean ?? 0),
    points: normalizeSpectrumPoints(payload?.points),
    algorithmVersion: typeof payload?.algorithmVersion === "string" ? payload.algorithmVersion : "",
    summaryMessage: typeof payload?.summaryMessage === "string" ? payload.summaryMessage : "",
    details: payload?.details && typeof payload.details === "object" ? payload.details : null,
    createdAt: typeof payload?.createdAt === "string" ? payload.createdAt : new Date().toISOString(),
});

const handleImageFrame = (timestamp: string, payload: any): void => {
    const frame = normalizeImageFrame(timestamp, payload);
    useJNIStore.getState().actions.pushImageFrame(frame);
    if (pendingFrame) {
        clearPendingRequest(pendingFrame);
        pendingFrame.resolve(frame);
        pendingFrame = null;
    }
};

/**
 * 后端会在FPGA错误、native完整性失败或服务器等待超时时发送capture_failed。
 * 这里立即结束“等待一帧”的Promise，不再让浏览器自己的定时器成为唯一失败来源。
 */
const handleCaptureFailed = (payload: any): void => {
    const code = typeof payload?.code === "string" ? payload.code : "CAPTURE_FAILED";
    const message = typeof payload?.message === "string" ? payload.message : "图像采集失败";
    pendingFrame = rejectPendingRequest(pendingFrame, `${message} (${code})`);
    useJNIStore.getState().actions.setError(`${message} (${code})`);
};

const handleStatus = (timestamp: string, payload: any): void => {
    const status: StatusRecord = {
        id: buildId("status"),
        timestamp,
        statusBits: Number(payload?.statusBits ?? 0),
        statusBinary: typeof payload?.statusBinary === "string" ? payload.statusBinary : "",
        errorCode: Number(payload?.errorCode ?? 0),
    };

    useJNIStore.getState().actions.pushStatus(status);
    if (pendingStatus) {
        clearPendingRequest(pendingStatus);
        pendingStatus.resolve(status);
        pendingStatus = null;
    }
};

const handleConfigAck = (timestamp: string, payload: any): void => {
    const ack: ConfigAckRecord = {
        id: buildId("config_ack"),
        timestamp,
        resultCode: Number(payload?.resultCode ?? -1),
        failedAddr: Number(payload?.failedAddr ?? -1),
    };

    useJNIStore.getState().actions.pushConfigAck(ack);
    if (pendingConfigAck) {
        clearPendingRequest(pendingConfigAck);
        pendingConfigAck.resolve(ack);
        pendingConfigAck = null;
    }
};

const handleTransportError = (timestamp: string, payload: any): void => {
    const transportError: TransportErrorRecord = {
        id: buildId("transport_error"),
        timestamp,
        channel: typeof payload?.channel === "string" ? payload.channel : "unknown",
        message: typeof payload?.message === "string" ? payload.message : "未知传输错误",
    };

    const store = useJNIStore.getState();
    store.actions.pushTransportError(transportError);
    store.actions.setError(transportError.message);
};

const handleEvent = (rawMessage: string): void => {
    let event: JniEventEnvelope;
    try {
        event = JSON.parse(rawMessage) as JniEventEnvelope;
    } catch {
        return;
    }

    const timestamp = typeof event.timestamp === "string" ? event.timestamp : new Date().toISOString();
    switch (event.type) {
        case "connection":
            applyConnectionState((event.payload ?? {}) as Partial<BridgeConnectionState>);
            return;
        case "image_frame":
            handleImageFrame(timestamp, event.payload);
            return;
        case "status":
            handleStatus(timestamp, event.payload);
            return;
        case "config_ack":
            handleConfigAck(timestamp, event.payload);
            return;
        case "transport_error":
            handleTransportError(timestamp, event.payload);
            return;
        case "capture_failed":
            handleCaptureFailed(event.payload);
            return;
        default:
            return;
    }
};

const ensureWebSocket = async (): Promise<void> => {
    if (bridgeWebSocket?.readyState === WebSocket.OPEN) {
        return;
    }

    if (websocketPromise) {
        return websocketPromise;
    }

    websocketPromise = new Promise<void>((resolve, reject) => {
        let settled = false;
        const socket = new WebSocket(getWebSocketUrl());
        const timeoutId = window.setTimeout(() => {
            if (settled) {
                return;
            }
            settled = true;
            socket.close();
            websocketPromise = null;
            useJNIStore.getState().actions.setWebsocketConnected(false);
            reject(new Error("连接 WebSocket 超时"));
        }, 10000);

        socket.onopen = () => {
            settled = true;
            window.clearTimeout(timeoutId);
            bridgeWebSocket = socket;
            websocketPromise = null;
            useJNIStore.getState().actions.setWebsocketConnected(true);
            resolve();
        };

        socket.onmessage = (event) => {
            if (typeof event.data === "string") {
                handleEvent(event.data);
            }
        };

        socket.onerror = () => {
            useJNIStore.getState().actions.setWebsocketConnected(false);
            if (!settled) {
                settled = true;
                window.clearTimeout(timeoutId);
                websocketPromise = null;
                reject(new Error("WebSocket 连接失败"));
            }
        };

        socket.onclose = () => {
            bridgeWebSocket = null;
            websocketPromise = null;
            useJNIStore.getState().actions.setWebsocketConnected(false);
            pendingFrame = rejectPendingRequest(pendingFrame, "图像通道已关闭");
            pendingStatus = rejectPendingRequest(pendingStatus, "状态通道已关闭");
            pendingConfigAck = rejectPendingRequest(pendingConfigAck, "配置应答通道已关闭");

            if (!settled) {
                settled = true;
                window.clearTimeout(timeoutId);
                reject(new Error("WebSocket 连接已关闭"));
            }
        };
    });

    return websocketPromise;
};

const createPendingRequest = <T>(
    currentPending: PendingRequest<T> | null,
    assign: (pending: PendingRequest<T> | null) => void,
    timeoutMs: number,
    timeoutMessage: string,
): Promise<T> => {
    if (currentPending) {
        throw new Error("已有同类命令正在等待回调结果");
    }

    return new Promise<T>((resolve, reject) => {
        const timeoutId = window.setTimeout(() => {
            assign(null);
            reject(new Error(timeoutMessage));
        }, timeoutMs);

        assign({
            timeoutId,
            resolve,
            reject,
        });
    });
};

const initialize = async (): Promise<BridgeConnectionState> => {
    await ensureWebSocket();
    const [state, frames] = await Promise.all([
        jniApi.getState(),
        jniApi.listImages(),
    ]);
    const normalizedFrames = frames.map((frame) => normalizeImageFrame(frame.timestamp, frame));
    applyConnectionState(state);
    useJNIStore.getState().actions.replaceImageHistory(normalizedFrames);
    return state;
};

/**
 * 独立加载数据库历史，不要求设备已连接，也不依赖WebSocket。
 * 首页和历史管理页刷新时会调用它。
 */
const loadImageHistory = async (): Promise<ImageFrameRecord[]> => {
    const frames = await jniApi.listImages();
    const normalizedFrames = frames.map((frame) => normalizeImageFrame(frame.timestamp, frame));
    useJNIStore.getState().actions.replaceImageHistory(normalizedFrames);
    return normalizedFrames;
};

const deleteImage = async (imageId: number): Promise<void> => {
    const deleted = await jniApi.deleteImage(imageId);
    if (deleted) {
        useJNIStore.getState().actions.removeImageFrame(imageId);
    }
};

const processImage = async (imageId: number): Promise<ImageFrameRecord> => {
    const frame = await jniApi.processImage(imageId);
    const normalizedFrame = normalizeImageFrame(frame.timestamp, frame);
    useJNIStore.getState().actions.pushImageFrame(normalizedFrame);
    return normalizedFrame;
};

const getImagePixels = async (
    imageId: number,
    request: ImagePixelDataRequest = {},
): Promise<ImagePixelDataRecord> => {
    const pixels = await jniApi.getImagePixels(imageId, request);
    return normalizeImagePixelData(pixels);
};

const extractSpectrum = async (
    imageId: number,
    request: SpectrumExtractionRequest = {},
): Promise<SpectrumExtractionRecord> => {
    const spectrum = await jniApi.extractSpectrum(imageId, request);
    return normalizeSpectrumExtraction(spectrum);
};

const getLatestSpectrum = async (imageId: number): Promise<SpectrumExtractionRecord | null> => {
    const spectrum = await jniApi.getLatestSpectrum(imageId);
    return spectrum ? normalizeSpectrumExtraction(spectrum) : null;
};

const clearImages = async (): Promise<void> => {
    await jniApi.clearImages();
    useJNIStore.getState().actions.clearImageHistory();
};

const analyzeMultiFrame = async (payload: MultiFrameAnalysisRequest): Promise<MultiFrameAnalysisRecord> => {
    const result = await jniApi.analyzeMultiFrame(payload);
    const normalizedFrames = (result.frames ?? []).map((frame) => normalizeImageFrame(frame.timestamp, frame));
    if (normalizedFrames.length > 0) {
        useJNIStore.getState().actions.replaceImageHistory(normalizedFrames);
    }
    return {
        ...result,
        frames: normalizedFrames,
        badPixelIndexes: Array.isArray(result.badPixelIndexes) ? result.badPixelIndexes : [],
        abnormalRows: Array.isArray(result.abnormalRows) ? result.abnormalRows : [],
        abnormalColumns: Array.isArray(result.abnormalColumns) ? result.abnormalColumns : [],
        analyzedImageIds: Array.isArray(result.analyzedImageIds) ? result.analyzedImageIds : [],
        repairedImageIds: Array.isArray(result.repairedImageIds) ? result.repairedImageIds : [],
    };
};

const simulateCalibration = async (
    type: "DARK" | "FLAT",
    payload?: CalibrationRequest,
): Promise<CalibrationSessionRecord> => jniApi.simulateCalibration(type, payload);

const buildCalibrationFromImages = async (
    type: "DARK" | "FLAT",
    payload: CalibrationRequest,
): Promise<CalibrationSessionRecord> => jniApi.buildCalibrationFromImages(type, payload);

const listCalibrations = async (type?: "DARK" | "FLAT"): Promise<CalibrationSessionRecord[]> =>
    jniApi.listCalibrations(type);

const listCalibrationPreviews = async (
    sessionId: number,
    limit = 6,
): Promise<CalibrationPreviewRecord[]> => jniApi.listCalibrationPreviews(sessionId, limit);

const getCalibrationGlobalSettings = async (): Promise<CalibrationGlobalSettingsRecord> =>
    jniApi.getCalibrationGlobalSettings();

const updateCalibrationGlobalSettings = async (
    payload: CalibrationGlobalSettingsRequest,
): Promise<CalibrationGlobalSettingsRecord> => jniApi.updateCalibrationGlobalSettings(payload);

const connect = async (override?: Partial<BridgeConnectionForm>): Promise<BridgeConnectionState> => {
    await ensureWebSocket();

    const store = useJNIStore.getState();
    const payload: BridgeConnectionForm = {
        ...store.connectionForm,
        ...override,
    };

    if (!payload.host.trim()) {
        throw new Error("host 不能为空");
    }
    if (payload.controlPort < 1 || payload.controlPort > 65535) {
        throw new Error("controlPort 必须在 1 到 65535 之间");
    }
    if (payload.imagePort < 1 || payload.imagePort > 65535) {
        throw new Error("imagePort 必须在 1 到 65535 之间");
    }

    const state = await jniApi.connect(payload);
    applyConnectionState(state);
    store.actions.setError(null);
    return state;
};

const disconnect = async (): Promise<BridgeConnectionState> => {
    await ensureWebSocket();
    const state = await jniApi.disconnect();
    applyConnectionState(state);
    pendingFrame = rejectPendingRequest(pendingFrame, "设备已断开连接");
    pendingStatus = rejectPendingRequest(pendingStatus, "设备已断开连接");
    pendingConfigAck = rejectPendingRequest(pendingConfigAck, "设备已断开连接");
    return state;
};

const sendReset = async (): Promise<void> => {
    ensureConnected();
    await ensureWebSocket();
    await jniApi.sendReset();
};

// 浏览器兜底超时略长于后端的15秒事务超时，使后端有机会先发送带数据库结果码的
// capture_failed事件；20秒只处理WebSocket异常等极端情况。
const triggerOnceAndWaitForFrame = async (
    options: TriggerCaptureOptions = { autoProcess: false },
    timeoutMs = 20000,
): Promise<ImageFrameRecord> => {
    ensureConnected();
    await ensureWebSocket();
    const waitForFrame = createPendingRequest(
        pendingFrame,
        (value) => {
            pendingFrame = value;
        },
        timeoutMs,
        "等待图像帧超时",
    );

    try {
        // 同步响应只表示触发已成功登记并发送；最终结果仍由image_frame或
        // capture_failed WebSocket事件完成pendingFrame。
        await jniApi.sendTriggerOnce(options);
    } catch (error) {
        pendingFrame = rejectPendingRequest(
            pendingFrame,
            error instanceof Error ? error.message : "发送单次触发命令失败",
        );
        throw error;
    }

    return waitForFrame;
};

const queryStatusAndWait = async (timeoutMs = 10000): Promise<StatusRecord> => {
    ensureConnected();
    await ensureWebSocket();
    const waitForStatus = createPendingRequest(
        pendingStatus,
        (value) => {
            pendingStatus = value;
        },
        timeoutMs,
        "等待状态回调超时",
    );

    try {
        await jniApi.sendQueryStatus();
    } catch (error) {
        pendingStatus = rejectPendingRequest(
            pendingStatus,
            error instanceof Error ? error.message : "发送状态查询命令失败",
        );
        throw error;
    }

    return waitForStatus;
};

const sendFullConfigAndWait = async (configBytes: number[], timeoutMs = 10000): Promise<ConfigAckRecord> => {
    ensureConnected();
    await ensureWebSocket();

    if (configBytes.length !== 512) {
        throw new Error("完整配置必须包含 512 个字节");
    }

    const waitForAck = createPendingRequest(
        pendingConfigAck,
        (value) => {
            pendingConfigAck = value;
        },
        timeoutMs,
        "等待配置应答超时",
    );

    try {
        await jniApi.sendFullConfig(configBytes);
    } catch (error) {
        pendingConfigAck = rejectPendingRequest(
            pendingConfigAck,
            error instanceof Error ? error.message : "发送完整配置失败",
        );
        throw error;
    }

    return waitForAck;
};

export const jniBridgeService = {
    initialize,
    loadImageHistory,
    connect,
    disconnect,
    sendReset,
    triggerOnceAndWaitForFrame,
    queryStatusAndWait,
    sendFullConfigAndWait,
    processImage,
    getImagePixels,
    extractSpectrum,
    getLatestSpectrum,
    deleteImage,
    clearImages,
    analyzeMultiFrame,
    simulateCalibration,
    buildCalibrationFromImages,
    listCalibrations,
    listCalibrationPreviews,
    getCalibrationGlobalSettings,
    updateCalibrationGlobalSettings,
};
