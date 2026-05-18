import apiClient from "../api/client";
import type { BridgeConnectionForm, BridgeConnectionState } from "../types/jni";

export const jniApi = {
    getState: () => apiClient.get<unknown, BridgeConnectionState>("/jni/state"),
    connect: (payload: BridgeConnectionForm) =>
        apiClient.post<unknown, BridgeConnectionState>("/jni/connect", payload),
    disconnect: () => apiClient.post<unknown, BridgeConnectionState>("/jni/disconnect"),
    sendReset: () => apiClient.post<unknown, string>("/jni/commands/reset"),
    sendTriggerOnce: () => apiClient.post<unknown, string>("/jni/commands/trigger-once"),
    sendQueryStatus: () => apiClient.post<unknown, string>("/jni/commands/query-status"),
    sendFullConfig: (configBytes: number[]) =>
        apiClient.post<unknown, string>("/jni/commands/full-config", { configBytes }),
};
