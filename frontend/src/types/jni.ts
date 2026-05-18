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

export interface ImageFrameRecord {
    id: string;
    timestamp: string;
    width: number;
    height: number;
    raw8Length: number;
    raw16Length: number;
    imageDataUrl: string;
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
