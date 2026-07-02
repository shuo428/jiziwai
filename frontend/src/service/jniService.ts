import apiClient from "../api/client";
import type {
    BridgeConnectionForm,
    BridgeConnectionState,
    ImageFrameRecord,
    TriggerCaptureOptions,
    TriggerCaptureResponse,
} from "../types/jni";

export const jniApi = {
    getState: () => apiClient.get<unknown, BridgeConnectionState>("/jni/state"),
    connect: (payload: BridgeConnectionForm) =>
        apiClient.post<unknown, BridgeConnectionState>("/jni/connect", payload),
    disconnect: () => apiClient.post<unknown, BridgeConnectionState>("/jni/disconnect"),
    sendReset: () => apiClient.post<unknown, string>("/jni/commands/reset"),
    sendTriggerOnce: (payload?: TriggerCaptureOptions) =>
        apiClient.post<unknown, TriggerCaptureResponse>(
            "/jni/commands/trigger-once",
            payload ?? { autoProcess: false },
        ),
    sendQueryStatus: () => apiClient.post<unknown, string>("/jni/commands/query-status"),
    sendFullConfig: (configBytes: number[]) =>
        apiClient.post<unknown, string>("/jni/commands/full-config", { configBytes }),
    listImages: () => apiClient.get<unknown, ImageFrameRecord[]>("/jni/images"),
    processImage: (imageId: number) => apiClient.post<unknown, ImageFrameRecord>(`/jni/images/${imageId}/process`),
    deleteImage: (imageId: number) => apiClient.delete<unknown, boolean>(`/jni/images/${imageId}`),
    clearImages: () => apiClient.delete<unknown, number>("/jni/images"),
};
