import apiClient from "../api/client";

export const jniApi = {
    startJNIBridge: () => apiClient.get('/jni/start'),
    stopJNIBridge: () => apiClient.get('/jni/stop'),
    captureImages: (count: number) => apiClient.post('/jni/capture', null, { params: { count } }),
};