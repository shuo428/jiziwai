export interface BridgeConnectionForm {
    host: string;
    controlPort: number;
    imagePort: number;
    verifyCrc: boolean;
}

export interface BridgeConnectionState extends BridgeConnectionForm {
    connected: boolean;
    lastError: string | null;
    message: string | null;
    fullConfigSize: number;
}

export interface QualityRecommendedAction {
    code: string;
    label: string;
    stage: string;
    severity: string;
    reason: string;
    repairable: boolean;
}

export interface ImageFrameRecord {
    id: number;
    captureId: number;
    requestId: string;
    timestamp: string;
    width: number;
    height: number;
    raw8Length: number;
    raw16Length: number;
    payloadLength: number;
    pixelFormat: string;
    imageDataUrl: string;
    integrityPassed: boolean | null;
    integrityResultCode: string | null;
    qualityStatus: string | null;
    pixelMin: number | null;
    pixelMax: number | null;
    pixelMean: number | null;
    pixelStddev: number | null;
    blackPixelRatio: number | null;
    saturationPixelRatio: number | null;
    abnormalRowCount: number | null;
    abnormalColumnCount: number | null;
    badPixelCount: number | null;
    qualitySummaryMessage: string | null;
    qualityDetails: Record<string, unknown> | null;
    dispositionStatus: string | null;
    usableForSpectral: boolean | null;
    dispositionMessage: string | null;
    recommendedActions: QualityRecommendedAction[];
    dispositionReasonCodes: string[];
}

export interface TriggerCaptureResponse {
    captureId: number;
    requestId: string;
}

export interface StatusRecord {
    id: string;
    timestamp: string;
    statusBits: number;
    statusBinary: string;
    errorCode: number;
}

export interface ConfigAckRecord {
    id: string;
    timestamp: string;
    resultCode: number;
    failedAddr: number;
}

export interface TransportErrorRecord {
    id: string;
    timestamp: string;
    channel: string;
    message: string;
}

export interface JniEventEnvelope<T = unknown> {
    type: string;
    timestamp: string;
    payload: T;
}
