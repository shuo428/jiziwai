import { create } from "zustand";
import { persist } from "zustand/middleware";
import { createJSONStorage } from "zustand/middleware";

interface JNIBridgeResult {
    status: string;
    message: string;
    data: string;
}

export interface SpectralData {
    id: string;
    index: number;
    data: string;
    timestamp: string;
    size: number;
}

type JNIStore = {
    isRunning: boolean;
    result: JNIBridgeResult | null;
    error: string | null;
    spectralDataList: SpectralData[];
    receivedCount: number;
    totalCount: number;
    isCapturing: boolean;
    actions: {
        setIsRunning: (isRunning: boolean) => void;
        setResult: (result: JNIBridgeResult | null) => void;
        setError: (error: string | null) => void;
        clearState: () => void;
        addSpectralData: (data: SpectralData) => void;
        removeSpectralData: (id: string) => void;
        clearSpectralData: () => void;
        setCapturing: (isCapturing: boolean, totalCount?: number) => void;
        incrementReceivedCount: () => void;
        resetCaptureState: () => void;
    }
}

export const useJNIStore = create<JNIStore>()(
    persist(
        (set) => ({
            isRunning: false,
            result: null,
            error: null,
            spectralDataList: [],
            receivedCount: 0,
            totalCount: 0,
            isCapturing: false,

            actions: {
                setIsRunning: (isRunning: boolean) => {
                    set({ isRunning });
                },
                setResult: (result: JNIBridgeResult | null) => {
                    set({ result });
                },
                setError: (error: string | null) => {
                    set({ error });
                },
                clearState: () => {
                    set({ isRunning: false, result: null, error: null });
                },
                addSpectralData: (data: SpectralData) => {
                    set((state) => ({
                        spectralDataList: [...state.spectralDataList, data]
                    }));
                },
                removeSpectralData: (id: string) => {
                    set((state) => ({
                        spectralDataList: state.spectralDataList.filter(item => item.id !== id)
                    }));
                },
                clearSpectralData: () => {
                    set({ spectralDataList: [] });
                },
                setCapturing: (isCapturing: boolean, totalCount?: number) => {
                    set({ 
                        isCapturing, 
                        totalCount: totalCount || 0,
                        receivedCount: 0 
                    });
                },
                incrementReceivedCount: () => {
                    set((state) => ({
                        receivedCount: state.receivedCount + 1
                    }));
                },
                resetCaptureState: () => {
                    set({ 
                        isCapturing: false, 
                        receivedCount: 0, 
                        totalCount: 0 
                    });
                },
            }
        }),
        {
            name: "jni-store",
            storage: createJSONStorage(() => localStorage),
            partialize: (state) => ({
                isRunning: state.isRunning,
                result: state.result,
                error: state.error,
                spectralDataList: state.spectralDataList,
            }),
        }
    )
);
