import { jniApi } from "./jniService";
import { useJNIStore } from "../store/jniStore";

let spectralWebSocket: WebSocket | null = null;
let connectingPromise: Promise<void> | null = null;
let captureCompletionPromise: Promise<number> | null = null;
let captureResolve: ((count: number) => void) | null = null;
let captureReject: ((error: Error) => void) | null = null;
let captureTimeoutId: number | null = null;

/**
 * 将单条光谱数据写入原有 `jniStore`。
 *
 * 这样无论入口来自“获取光谱数据”页面还是“AI 助手”页面，
 * 最终都会落到同一个数据存储位置，不会出现功能分叉。
 *
 * @param rawData 单条光谱数据原文
 */
const appendSpectralRecord = (rawData: string): void => {
    const state = useJNIStore.getState();
    state.actions.addSpectralData({
        id: `spectral_${Date.now()}_${Math.random().toString(36).slice(2, 9)}`,
        index: state.spectralDataList.length + 1,
        data: rawData,
        timestamp: new Date().toISOString(),
        size: new Blob([rawData]).size,
    });
};

/**
 * 清理“按数量采集”模式的挂起状态。
 *
 * @param error 若存在，表示本轮采集以失败结束
 */
const finishCountCapture = (error?: Error): void => {
    const state = useJNIStore.getState();

    if (captureTimeoutId !== null) {
        window.clearTimeout(captureTimeoutId);
        captureTimeoutId = null;
    }

    if (error) {
        state.actions.resetCaptureState();
        state.actions.setError(error.message);
        if (captureReject) {
            captureReject(error);
        }
    }

    captureCompletionPromise = null;
    captureResolve = null;
    captureReject = null;

    if (!state.isRunning && spectralWebSocket) {
        spectralWebSocket.close();
        spectralWebSocket = null;
    }
};

/**
 * 处理 WebSocket 推送进来的单条光谱数据。
 *
 * 行为分两类：
 * 1. 持续监听模式：持续写入 `jniStore`
 * 2. 按数量采集模式：写入 `jniStore` 的同时推进采集进度，并在达到目标数量时结束本轮采集
 *
 * @param rawData 单条光谱数据原文
 */
const handleSpectralMessage = (rawData: string): void => {
    appendSpectralRecord(rawData);

    const state = useJNIStore.getState();
    if (!state.isCapturing) {
        return;
    }

    const nextReceivedCount = state.receivedCount + 1;
    state.actions.incrementReceivedCount();

    if (nextReceivedCount >= state.totalCount) {
        state.actions.resetCaptureState();
        if (captureTimeoutId !== null) {
            window.clearTimeout(captureTimeoutId);
            captureTimeoutId = null;
        }
        if (captureResolve) {
            captureResolve(nextReceivedCount);
        }
        captureCompletionPromise = null;
        captureResolve = null;
        captureReject = null;

        if (!useJNIStore.getState().isRunning && spectralWebSocket) {
            spectralWebSocket.close();
            spectralWebSocket = null;
        }
    }
};

/**
 * 建立并复用光谱 WebSocket 连接。
 *
 * 连接由服务层持有，而不是页面组件持有，目的是避免：
 * - AI 助手页卸载后连接被销毁
 * - “获取光谱数据”页和“AI 助手”页各自维护一套互相割裂的连接逻辑
 *
 * @returns Promise<void> 连接建立成功时 resolve；失败时 reject
 */
const ensureSpectralWebSocket = async (): Promise<void> => {
    if (spectralWebSocket?.readyState === WebSocket.OPEN) {
        return;
    }

    if (connectingPromise) {
        return connectingPromise;
    }

    connectingPromise = new Promise<void>((resolve, reject) => {
        let settled = false;
        const ws = new WebSocket("ws://127.0.0.1:8080/ws");
        const timeoutId = window.setTimeout(() => {
            if (settled) {
                return;
            }
            settled = true;
            ws.close();
            connectingPromise = null;
            reject(new Error("连接光谱数据通道超时"));
        }, 10000);

        ws.onopen = () => {
            if (settled) {
                return;
            }
            settled = true;
            window.clearTimeout(timeoutId);
            spectralWebSocket = ws;
            connectingPromise = null;
            resolve();
        };

        ws.onmessage = (event) => {
            if (typeof event.data !== "string") {
                return;
            }
            handleSpectralMessage(event.data);
        };

        ws.onerror = () => {
            if (!settled) {
                settled = true;
                window.clearTimeout(timeoutId);
                connectingPromise = null;
                reject(new Error("光谱数据 WebSocket 连接失败"));
            }

            if (useJNIStore.getState().isCapturing) {
                finishCountCapture(new Error("按数量采集过程中 WebSocket 连接失败"));
            }
        };

        ws.onclose = () => {
            if (spectralWebSocket === ws) {
                spectralWebSocket = null;
            }
            connectingPromise = null;

            if (!settled) {
                settled = true;
                window.clearTimeout(timeoutId);
                reject(new Error("光谱数据 WebSocket 已关闭"));
            }

            if (useJNIStore.getState().isCapturing) {
                finishCountCapture(new Error("按数量采集过程中 WebSocket 已关闭"));
            }
        };
    });

    return connectingPromise;
};

/**
 * 启动持续监听。
 *
 * 这是对你原有“开始监听”流程的服务化封装：
 * 1. 保证 WebSocket 已连接
 * 2. 调用现有 `/jni/start`
 * 3. 把运行状态写回 `jniStore`
 *
 * @returns Promise<number> 启动时当前已经累计的光谱数据数量
 */
const startContinuousListener = async (): Promise<number> => {
    const state = useJNIStore.getState();
    if (state.isCapturing) {
        throw new Error("当前正在按数量采集，无法同时启动持续监听");
    }
    if (state.isRunning) {
        return state.spectralDataList.length;
    }

    state.actions.setError(null);
    await ensureSpectralWebSocket();

    try {
        await jniApi.startJNIBridge();
        state.actions.setIsRunning(true);
        state.actions.setResult({
            status: "success",
            message: "持续监听已启动",
            data: "系统正在持续接收光谱数据",
        });
        return useJNIStore.getState().spectralDataList.length;
    } catch (error) {
        if (!useJNIStore.getState().isCapturing && spectralWebSocket) {
            spectralWebSocket.close();
            spectralWebSocket = null;
        }
        throw error;
    }
};

/**
 * 停止持续监听。
 *
 * 这是对你原有“停止监听”流程的服务化封装：
 * 1. 调用现有 `/jni/stop`
 * 2. 更新 `jniStore` 中的运行状态
 * 3. 如果当前没有按数量采集任务在运行，则关闭共享 WebSocket
 *
 * @returns Promise<number> 停止时累计已保存的光谱数据数量
 */
const stopContinuousListener = async (): Promise<number> => {
    const state = useJNIStore.getState();
    if (!state.isRunning) {
        return state.spectralDataList.length;
    }

    state.actions.setError(null);
    await jniApi.stopJNIBridge();
    state.actions.setIsRunning(false);
    state.actions.setResult(null);

    if (!useJNIStore.getState().isCapturing && spectralWebSocket) {
        spectralWebSocket.close();
        spectralWebSocket = null;
    }

    return useJNIStore.getState().spectralDataList.length;
};

/**
 * 按指定数量采集光谱数据。
 *
 * 这是对你原有“按数量采集”流程的服务化封装：
 * 1. 保证 WebSocket 已连接
 * 2. 调用现有 `/jni/capture`
 * 3. 通过共享 WebSocket 接收数据并写入 `jniStore`
 * 4. 达到指定数量后自动结束本轮采集
 *
 * @param count 期望采集的数据条数
 * @returns Promise<number> 本轮实际接收到的光谱数据数量
 */
const captureCountBasedSpectralData = async (count: number): Promise<number> => {
    const state = useJNIStore.getState();
    if (state.isRunning) {
        throw new Error("当前正在持续监听，请先停止监听后再进行按数量采集");
    }
    if (state.isCapturing) {
        throw new Error("当前已有按数量采集任务在执行");
    }

    const safeCount = Math.max(1, Math.min(count, 10));
    state.actions.setError(null);
    await ensureSpectralWebSocket();
    state.actions.setCapturing(true, safeCount);

    captureCompletionPromise = new Promise<number>((resolve, reject) => {
        captureResolve = resolve;
        captureReject = reject;
        captureTimeoutId = window.setTimeout(() => {
            finishCountCapture(new Error("等待光谱数据超时"));
        }, 20000);
    });

    try {
        await jniApi.captureImages(safeCount);
    } catch (error) {
        finishCountCapture(error instanceof Error ? error : new Error("启动按数量采集失败"));
        throw error;
    }

    return captureCompletionPromise;
};

export const jniBridgeService = {
    startContinuousListener,
    stopContinuousListener,
    captureCountBasedSpectralData,
};
