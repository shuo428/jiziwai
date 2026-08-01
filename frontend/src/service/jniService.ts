import apiClient from "../api/client";
import type {
    BridgeConnectionForm,
    BridgeConnectionState,
    CalibrationRequest,
    CalibrationGlobalSettingsRecord,
    CalibrationGlobalSettingsRequest,
    CalibrationPreviewRecord,
    CalibrationSessionRecord,
    ImageFrameRecord,
    ImagePixelDataRecord,
    ImagePixelDataRequest,
    MultiFrameAnalysisRecord,
    MultiFrameAnalysisRequest,
    SpectrumExtractionRecord,
    SpectrumExtractionRequest,
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
    getImagePixels: (imageId: number, payload?: ImagePixelDataRequest) =>
        apiClient.get<unknown, ImagePixelDataRecord>(`/jni/images/${imageId}/pixels`, {
            params: payload,
        }),
    getLatestSpectrum: (imageId: number) =>
        apiClient.get<unknown, SpectrumExtractionRecord | null>(`/jni/images/${imageId}/spectrum`),
    extractSpectrum: (imageId: number, payload?: SpectrumExtractionRequest) =>
        apiClient.post<unknown, SpectrumExtractionRecord>(`/jni/images/${imageId}/spectrum/extract`, payload ?? {}),
    deleteImage: (imageId: number) => apiClient.delete<unknown, boolean>(`/jni/images/${imageId}`),
    clearImages: () => apiClient.delete<unknown, number>("/jni/images"),
    analyzeMultiFrame: (payload: MultiFrameAnalysisRequest) =>
        apiClient.post<unknown, MultiFrameAnalysisRecord>("/jni/images/multi-frame/analyze", payload),
    simulateCalibration: (type: "DARK" | "FLAT", payload?: CalibrationRequest) =>
        apiClient.post<unknown, CalibrationSessionRecord>(`/jni/calibrations/${type}/simulate`, payload ?? {}),
    buildCalibrationFromImages: (type: "DARK" | "FLAT", payload: CalibrationRequest) =>
        apiClient.post<unknown, CalibrationSessionRecord>(`/jni/calibrations/${type}/from-images`, payload),
    listCalibrations: (type?: "DARK" | "FLAT") =>
        apiClient.get<unknown, CalibrationSessionRecord[]>(
            "/jni/calibrations",
            type ? { params: { type } } : undefined,
        ),
    getCalibration: (sessionId: number) =>
        apiClient.get<unknown, CalibrationSessionRecord>(`/jni/calibrations/${sessionId}`),
    listCalibrationPreviews: (sessionId: number, limit = 6) =>
        apiClient.get<unknown, CalibrationPreviewRecord[]>(`/jni/calibrations/${sessionId}/previews`, {
            params: { limit },
        }),
    getCalibrationGlobalSettings: () =>
        apiClient.get<unknown, CalibrationGlobalSettingsRecord>("/jni/calibrations/settings"),
    updateCalibrationGlobalSettings: (payload: CalibrationGlobalSettingsRequest) =>
        apiClient.put<unknown, CalibrationGlobalSettingsRecord>("/jni/calibrations/settings", payload),
};
