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
    workMode: WorkMode;
    connectionForm: BridgeConnectionForm;
    bridgeState: BridgeConnectionState;
    savedConnectionOptions: SavedConnectionOptions;
    websocketConnected: boolean;
    error: string | null;
    currentImage: ImageFrameRecord | null;
    imageHistory: ImageFrameRecord[];
    currentHdrImage: ImageFrameRecord | null;
    hdrImageHistory: ImageFrameRecord[];
    latestStatus: StatusRecord | null;
    statusHistory: StatusRecord[];
    latestConfigAck: ConfigAckRecord | null;
    transportErrors: TransportErrorRecord[];
    configBytes: number[];
    autoProcessAfterCapture: boolean;
    actions: {
        setWorkMode: (mode: WorkMode) => void;
        setConnectionField: <K extends keyof BridgeConnectionForm>(field: K, value: BridgeConnectionForm[K]) => void;
        saveConnectionOption: (field: SavedConnectionField, value?: string | number) => void;
        removeConnectionOption: (field: SavedConnectionField, value: string | number) => void;
        hydrateBridgeState: (state: Partial<BridgeConnectionState>) => void;
        setWebsocketConnected: (connected: boolean) => void;
        setError: (error: string | null) => void;
        setAutoProcessAfterCapture: (enabled: boolean) => void;
        pushImageFrame: (frame: ImageFrameRecord) => void;
        replaceImageHistory: (frames: ImageFrameRecord[]) => void;
        setCurrentHdrImage: (frame: ImageFrameRecord | null) => void;
        pushHdrImageFrame: (frame: ImageFrameRecord) => void;
        replaceHdrImageHistory: (frames: ImageFrameRecord[]) => void;
        removeImageFrame: (id: number) => void;
        clearImageHistory: () => void;
        pushStatus: (status: StatusRecord) => void;
        pushConfigAck: (ack: ConfigAckRecord) => void;
        pushTransportError: (error: TransportErrorRecord) => void;
        setConfigByte: (index: number, value: number) => void;
        replaceConfigBytes: (values: number[]) => void;
        resetConfigBytes: () => void;
    };
};

export type WorkMode = "NORMAL" | "HDR";

export type SavedConnectionOptions = {
    hosts: string[];
    controlPorts: number[];
    imagePorts: number[];
};

type SavedConnectionField = "host" | "controlPort" | "imagePort";

const DEFAULT_CONFIG_BYTES = Array.from({ length: 512 }, () => 0);
const SAVED_CONNECTION_OPTION_LIMIT = 30;

const DEFAULT_CONNECTION_FORM: BridgeConnectionForm = {
    host: "",
    controlPort: 0,
    imagePort: 0,
    verifyCrc: true,
    expectedWidth: 800,
    expectedHeight: 600,
    pixelFormat: "RAW16_LOW12",
    readoutOrder: "GLUX1605_HDR_4LANE_INTERLEAVED_EFFECTIVE",
};

const DEFAULT_BRIDGE_STATE: BridgeConnectionState = {
    ...DEFAULT_CONNECTION_FORM,
    connected: false,
    lastError: null,
    message: null,
    fullConfigSize: 512,
};

const DEFAULT_SAVED_CONNECTION_OPTIONS: SavedConnectionOptions = {
    hosts: [],
    controlPorts: [],
    imagePorts: [],
};

const normalizeHostOption = (value: unknown): string => String(value ?? "").trim();

const normalizePortOption = (value: unknown): number => {
    const port = Number(value);
    if (!Number.isInteger(port) || port < 1 || port > 65535) {
        return 0;
    }
    return port;
};

const normalizeSavedConnectionOptions = (value: any): SavedConnectionOptions => ({
    hosts: Array.isArray(value?.hosts)
        ? value.hosts
              .map(normalizeHostOption)
              .filter(Boolean)
              .filter((item: string, index: number, array: string[]) => array.indexOf(item) === index)
              .slice(0, SAVED_CONNECTION_OPTION_LIMIT)
        : [],
    controlPorts: Array.isArray(value?.controlPorts)
        ? value.controlPorts
              .map(normalizePortOption)
              .filter(Boolean)
              .filter((item: number, index: number, array: number[]) => array.indexOf(item) === index)
              .slice(0, SAVED_CONNECTION_OPTION_LIMIT)
        : [],
    imagePorts: Array.isArray(value?.imagePorts)
        ? value.imagePorts
              .map(normalizePortOption)
              .filter(Boolean)
              .filter((item: number, index: number, array: number[]) => array.indexOf(item) === index)
              .slice(0, SAVED_CONNECTION_OPTION_LIMIT)
        : [],
});

const addStringOption = (items: string[], value: string) =>
    [value, ...items.filter((item) => item !== value)].slice(0, SAVED_CONNECTION_OPTION_LIMIT);

const addNumberOption = (items: number[], value: number) =>
    [value, ...items.filter((item) => item !== value)].slice(0, SAVED_CONNECTION_OPTION_LIMIT);

export const useJNIStore = create<JNIStore>()(
    persist(
        (set) => ({
            workMode: "NORMAL",
            connectionForm: DEFAULT_CONNECTION_FORM,
            bridgeState: DEFAULT_BRIDGE_STATE,
            savedConnectionOptions: DEFAULT_SAVED_CONNECTION_OPTIONS,
            websocketConnected: false,
            error: null,
            currentImage: null,
            imageHistory: [],
            currentHdrImage: null,
            hdrImageHistory: [],
            latestStatus: null,
            statusHistory: [],
            latestConfigAck: null,
            transportErrors: [],
            configBytes: DEFAULT_CONFIG_BYTES,
            autoProcessAfterCapture: false,

            actions: {
                setWorkMode: (mode) => {
                    set({ workMode: mode });
                },
                setConnectionField: (field, value) => {
                    set((state) => ({
                        connectionForm: {
                            ...state.connectionForm,
                            [field]: value,
                        },
                    }));
                },
                saveConnectionOption: (field, value) => {
                    set((state) => {
                        const nextOptions = normalizeSavedConnectionOptions(state.savedConnectionOptions);
                        if (field === "host") {
                            const host = normalizeHostOption(value ?? state.connectionForm.host);
                            if (!host) {
                                return { savedConnectionOptions: nextOptions };
                            }
                            return {
                                savedConnectionOptions: {
                                    ...nextOptions,
                                    hosts: addStringOption(nextOptions.hosts, host),
                                },
                            };
                        }

                        if (field === "controlPort") {
                            const port = normalizePortOption(value ?? state.connectionForm.controlPort);
                            if (!port) {
                                return { savedConnectionOptions: nextOptions };
                            }
                            return {
                                savedConnectionOptions: {
                                    ...nextOptions,
                                    controlPorts: addNumberOption(nextOptions.controlPorts, port),
                                },
                            };
                        }

                        const port = normalizePortOption(value ?? state.connectionForm.imagePort);
                        if (!port) {
                            return { savedConnectionOptions: nextOptions };
                        }
                        return {
                            savedConnectionOptions: {
                                ...nextOptions,
                                imagePorts: addNumberOption(nextOptions.imagePorts, port),
                            },
                        };
                    });
                },
                removeConnectionOption: (field, value) => {
                    set((state) => {
                        const nextOptions = normalizeSavedConnectionOptions(state.savedConnectionOptions);
                        if (field === "host") {
                            const host = normalizeHostOption(value);
                            return {
                                savedConnectionOptions: {
                                    ...nextOptions,
                                    hosts: nextOptions.hosts.filter((item) => item !== host),
                                },
                            };
                        }

                        const port = normalizePortOption(value);
                        if (field === "controlPort") {
                            return {
                                savedConnectionOptions: {
                                    ...nextOptions,
                                    controlPorts: nextOptions.controlPorts.filter((item) => item !== port),
                                },
                            };
                        }
                        return {
                            savedConnectionOptions: {
                                ...nextOptions,
                                imagePorts: nextOptions.imagePorts.filter((item) => item !== port),
                            },
                        };
                    });
                },
                hydrateBridgeState: (incomingState) => {
                    set((state) => {
                        const nextBridgeState = {
                            ...state.bridgeState,
                            ...incomingState,
                        };
                        const shouldSyncConnectionForm = Boolean(incomingState.connected);
                        return {
                            bridgeState: nextBridgeState,
                            connectionForm: shouldSyncConnectionForm
                                ? {
                                      host: incomingState.host ?? state.connectionForm.host,
                                      controlPort: incomingState.controlPort ?? state.connectionForm.controlPort,
                                      imagePort: incomingState.imagePort ?? state.connectionForm.imagePort,
                                      verifyCrc: incomingState.verifyCrc ?? state.connectionForm.verifyCrc,
                                      expectedWidth: incomingState.expectedWidth ?? state.connectionForm.expectedWidth,
                                      expectedHeight: incomingState.expectedHeight ?? state.connectionForm.expectedHeight,
                                      pixelFormat: incomingState.pixelFormat ?? state.connectionForm.pixelFormat,
                                      readoutOrder: incomingState.readoutOrder ?? state.connectionForm.readoutOrder,
                                  }
                                : state.connectionForm,
                        };
                    });
                },
                setWebsocketConnected: (connected) => set({ websocketConnected: connected }),
                setError: (error: string | null) => {
                    set({ error });
                },
                setAutoProcessAfterCapture: (enabled) => {
                    set({ autoProcessAfterCapture: enabled });
                },
                pushImageFrame: (frame) => {
                    set((state) => ({
                        currentImage: frame,
                        imageHistory: [frame, ...state.imageHistory.filter((item) => item.id !== frame.id)].slice(0, 50),
                    }));
                },
                replaceImageHistory: (frames) => {
                    set({
                        imageHistory: frames,
                        currentImage: frames[0] ?? null,
                    });
                },
                setCurrentHdrImage: (frame) => {
                    set({ currentHdrImage: frame });
                },
                pushHdrImageFrame: (frame) => {
                    set((state) => ({
                        currentHdrImage: frame,
                        hdrImageHistory: [frame, ...state.hdrImageHistory.filter((item) => item.id !== frame.id)].slice(0, 50),
                    }));
                },
                replaceHdrImageHistory: (frames) => {
                    set({
                        hdrImageHistory: frames,
                        currentHdrImage: frames[0] ?? null,
                    });
                },
                removeImageFrame: (id) => {
                    set((state) => ({
                        currentImage: state.currentImage?.id === id ? null : state.currentImage,
                        imageHistory: state.imageHistory.filter((item) => item.id !== id),
                        currentHdrImage: state.currentHdrImage?.id === id ? null : state.currentHdrImage,
                        hdrImageHistory: state.hdrImageHistory.filter((item) => item.id !== id),
                    }));
                },
                clearImageHistory: () => {
                    set({ currentImage: null, imageHistory: [], currentHdrImage: null, hdrImageHistory: [] });
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
            version: 6,
            // 版本1曾把完整PNG data URL保存在localStorage中。升级到版本2后只迁移
            // 连接表单和512字节配置，历史图片改为登录后从PostgreSQL加载。
            // 版本3追加“获取后自动处理”开关，避免切换页面或刷新后丢失用户选择。
            // 版本4追加采集图像全局尺寸/像素格式/读出顺序配置。
            // 版本5追加常用 Host、Control Port、Image Port 保存项，设备总览可下拉选择。
            // 版本6追加普通/HDR全局工作模式；普通当前帧与HDR当前帧分开管理。
            migrate: (persistedState: any) => ({
                workMode: persistedState?.workMode === "HDR" ? "HDR" : "NORMAL",
                connectionForm: {
                    ...DEFAULT_CONNECTION_FORM,
                    ...(persistedState?.connectionForm ?? {}),
                },
                savedConnectionOptions: normalizeSavedConnectionOptions(
                    persistedState?.savedConnectionOptions ?? DEFAULT_SAVED_CONNECTION_OPTIONS,
                ),
                configBytes: persistedState?.configBytes ?? DEFAULT_CONFIG_BYTES,
                autoProcessAfterCapture: persistedState?.autoProcessAfterCapture ?? false,
            }),
            partialize: (state) => ({
                workMode: state.workMode,
                connectionForm: state.connectionForm,
                savedConnectionOptions: state.savedConnectionOptions,
                configBytes: state.configBytes,
                autoProcessAfterCapture: state.autoProcessAfterCapture,
            }),
        }
    )
);
