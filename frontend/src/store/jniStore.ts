import { create } from "zustand";
import { persist } from "zustand/middleware";
import { createJSONStorage } from "zustand/middleware";
import type {
    BridgeConnectionForm,
    BridgeConnectionState,
    ConfigAckRecord,
    ImageFrameRecord,
    StatusRecord,
    TransportErrorRecord,
} from "../types/jni";

type JNIStore = {
    connectionForm: BridgeConnectionForm;
    bridgeState: BridgeConnectionState;
    websocketConnected: boolean;
    error: string | null;
    currentImage: ImageFrameRecord | null;
    imageHistory: ImageFrameRecord[];
    latestStatus: StatusRecord | null;
    statusHistory: StatusRecord[];
    latestConfigAck: ConfigAckRecord | null;
    transportErrors: TransportErrorRecord[];
    configBytes: number[];
    actions: {
        setConnectionField: <K extends keyof BridgeConnectionForm>(field: K, value: BridgeConnectionForm[K]) => void;
        hydrateBridgeState: (state: Partial<BridgeConnectionState>) => void;
        setWebsocketConnected: (connected: boolean) => void;
        setError: (error: string | null) => void;
        pushImageFrame: (frame: ImageFrameRecord) => void;
        removeImageFrame: (id: string) => void;
        clearImageHistory: () => void;
        pushStatus: (status: StatusRecord) => void;
        pushConfigAck: (ack: ConfigAckRecord) => void;
        pushTransportError: (error: TransportErrorRecord) => void;
        setConfigByte: (index: number, value: number) => void;
        replaceConfigBytes: (values: number[]) => void;
        resetConfigBytes: () => void;
    };
};

const DEFAULT_CONFIG_BYTES = Array.from({ length: 512 }, () => 0);

const DEFAULT_CONNECTION_FORM: BridgeConnectionForm = {
    host: "",
    controlPort: 0,
    imagePort: 0,
    verifyCrc: true,
};

const DEFAULT_BRIDGE_STATE: BridgeConnectionState = {
    ...DEFAULT_CONNECTION_FORM,
    connected: false,
    lastError: null,
    message: null,
    fullConfigSize: 512,
};

export const useJNIStore = create<JNIStore>()(
    persist(
        (set) => ({
            connectionForm: DEFAULT_CONNECTION_FORM,
            bridgeState: DEFAULT_BRIDGE_STATE,
            websocketConnected: false,
            error: null,
            currentImage: null,
            imageHistory: [],
            latestStatus: null,
            statusHistory: [],
            latestConfigAck: null,
            transportErrors: [],
            configBytes: DEFAULT_CONFIG_BYTES,

            actions: {
                setConnectionField: (field, value) => {
                    set((state) => ({
                        connectionForm: {
                            ...state.connectionForm,
                            [field]: value,
                        },
                    }));
                },
                hydrateBridgeState: (incomingState) => {
                    set((state) => ({
                        bridgeState: {
                            ...state.bridgeState,
                            ...incomingState,
                        },
                        connectionForm: {
                            host: incomingState.host ?? state.connectionForm.host,
                            controlPort: incomingState.controlPort ?? state.connectionForm.controlPort,
                            imagePort: incomingState.imagePort ?? state.connectionForm.imagePort,
                            verifyCrc: incomingState.verifyCrc ?? state.connectionForm.verifyCrc,
                        },
                    }));
                },
                setWebsocketConnected: (connected) => set({ websocketConnected: connected }),
                setError: (error: string | null) => {
                    set({ error });
                },
                pushImageFrame: (frame) => {
                    set((state) => ({
                        currentImage: frame,
                        imageHistory: [frame, ...state.imageHistory.filter((item) => item.id !== frame.id)].slice(0, 50),
                    }));
                },
                removeImageFrame: (id) => {
                    set((state) => ({
                        currentImage: state.currentImage?.id === id ? null : state.currentImage,
                        imageHistory: state.imageHistory.filter((item) => item.id !== id),
                    }));
                },
                clearImageHistory: () => {
                    set({ currentImage: null, imageHistory: [] });
                },
                pushStatus: (status) => {
                    set((state) => ({
                        latestStatus: status,
                        statusHistory: [status, ...state.statusHistory.filter((item) => item.id !== status.id)].slice(0, 50),
                    }));
                },
                pushConfigAck: (ack) => {
                    set({ latestConfigAck: ack });
                },
                pushTransportError: (transportError) => {
                    set((state) => ({
                        transportErrors: [transportError, ...state.transportErrors].slice(0, 50),
                    }));
                },
                setConfigByte: (index, value) => {
                    set((state) => {
                        const nextConfigBytes = [...state.configBytes];
                        nextConfigBytes[index] = Math.max(0, Math.min(255, Math.trunc(value)));
                        return { configBytes: nextConfigBytes };
                    });
                },
                replaceConfigBytes: (values) => {
                    const normalized = Array.from({ length: DEFAULT_CONFIG_BYTES.length }, (_, index) => {
                        const value = values[index] ?? 0;
                        return Math.max(0, Math.min(255, Math.trunc(value)));
                    });
                    set({ configBytes: normalized });
                },
                resetConfigBytes: () => {
                    set({ configBytes: [...DEFAULT_CONFIG_BYTES] });
                },
            },
        }),
        {
            name: "jni-store",
            storage: createJSONStorage(() => localStorage),
            partialize: (state) => ({
                connectionForm: state.connectionForm,
                imageHistory: state.imageHistory,
                configBytes: state.configBytes,
            }),
        }
    )
);
