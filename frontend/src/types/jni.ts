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
    rawHardQualitySnapshot: Record<string, unknown> | null;
    calibratedQualitySnapshot: Record<string, unknown> | null;
    originalQualitySnapshot: Record<string, unknown> | null;
    dispositionStatus: string | null;
    usableForSpectral: boolean | null;
    dispositionMessage: string | null;
    recommendedActions: QualityRecommendedAction[];
    dispositionReasonCodes: string[];
    processedImageDataUrl: string;
    processingStatus: string | null;
    processingMessage: string | null;
    executedProcessingActions: Array<Record<string, unknown>>;
    processedQualityStatus: string | null;
    processedPixelMin: number | null;
    processedPixelMax: number | null;
    processedPixelMean: number | null;
    processedPixelStddev: number | null;
    processedBlackPixelRatio: number | null;
    processedSaturationPixelRatio: number | null;
    processedAbnormalRowCount: number | null;
    processedAbnormalColumnCount: number | null;
    processedBadPixelCount: number | null;
    processedQualitySummaryMessage: string | null;
    processedQualityDetails: Record<string, unknown> | null;
    processedQualitySnapshot: Record<string, unknown> | null;
    processedDispositionStatus: string | null;
    processedUsableForSpectral: boolean | null;
    processedDispositionMessage: string | null;
}

export type ImagePixelSourceMode = "ORIGINAL" | "PROCESSED";
export type ImagePixelDisplayFormat = "DN" | "HEX_WORD" | "HEX_FILE";

export interface ImagePixelDataRequest {
    source?: ImagePixelSourceMode;
    format?: ImagePixelDisplayFormat;
    fullFrame?: boolean;
    xStart?: number;
    yStart?: number;
    width?: number;
    height?: number;
}

export interface ImagePixelDataRecord {
    imageId: number;
    sourceMode: ImagePixelSourceMode | string;
    width: number;
    height: number;
    xStart: number;
    yStart: number;
    xEnd: number;
    yEnd: number;
    roiWidth: number;
    roiHeight: number;
    storageBitDepth: number;
    effectiveBitDepth: number;
    pixelFormat: string;
    displayFormat: ImagePixelDisplayFormat | string;
    rawFileByteOrder: string;
    fullFrame: boolean;
    pixelMin: number;
    pixelMax: number;
    pixelMean: number;
    rows: number[][];
    hexRows: string[];
}

export interface TriggerCaptureResponse {
    captureId: number;
    requestId: string;
}

export interface TriggerCaptureOptions {
    autoProcess: boolean;
}

export interface CalibrationRequest {
    frameCount?: number;
    width?: number;
    height?: number;
    imageIds?: number[];
}

export interface CalibrationSessionRecord {
    id: number;
    sessionNumber: number;
    calibrationType: "DARK" | "FLAT";
    acquisitionMode: string;
    status: string;
    expectedFrameCount: number;
    frameCount: number;
    width: number;
    height: number;
    badPixelCount: number;
    badRowCount: number;
    badColumnCount: number;
    storageUri: string | null;
    defectMapUri: string | null;
    message: string | null;
    summary: Record<string, unknown> | null;
    createdAt: string;
    completedAt: string | null;
}

export interface CalibrationPreviewRecord {
    frameIndex: number;
    imageDataUrl: string;
    storageUri: string | null;
}

/** 用户级全局暗场/平场校准状态。 */
export interface CalibrationGlobalSettingsRecord {
    enabled: boolean;
    darkCalibrationId: number | null;
    flatCalibrationId: number | null;
    defectMapEnabled: boolean;
    calibrationPackageReady: boolean;
    defectMapAvailable: boolean;
    width: number | null;
    height: number | null;
    darkReferenceAvailable: boolean;
    flatReferenceAvailable: boolean;
    updatedAt: string | null;
    message: string;
}

export interface CalibrationGlobalSettingsRequest {
    enabled: boolean;
    darkCalibrationId: number | null;
    flatCalibrationId: number | null;
    defectMapEnabled: boolean;
}

export interface MultiFrameAnalysisRequest {
    imageIds: number[];
    repair?: boolean;
    voteRatio?: number;
}

export interface MultiFrameAnalysisRecord {
    mode: string;
    frameCount: number;
    width: number;
    height: number;
    voteRatio: number;
    badPixelCount: number;
    abnormalRowCount: number;
    abnormalColumnCount: number;
    badPixelIndexes: number[];
    abnormalRows: number[];
    abnormalColumns: number[];
    analyzedImageIds: number[];
    repairedImageIds: number[];
    summaryMessage: string;
    details: Record<string, unknown> | null;
    frames: ImageFrameRecord[];
}

export interface SpectrumRoi {
    xStart: number;
    xEnd: number;
    yStart: number;
    yEnd: number;
}

export interface SpectrumExtractionRequest {
    sourceMode?: "AUTO" | "ORIGINAL" | "PROCESSED";
    wavelengthAxis?: "AUTO" | "X" | "Y";
    rectifyTilt?: boolean;
    maxShiftPixels?: number;
    integrationMethod?: "MEAN" | "SUM";
    roi?: Partial<SpectrumRoi>;
}

export interface SpectrumPoint {
    pixelIndex: number;
    intensity: number;
}

export interface SpectrumExtractionRecord {
    id: number;
    imageId: number;
    captureId: number;
    sourceMode: string;
    sourceQualityStatus: string;
    wavelengthAxis: string;
    roi: SpectrumRoi;
    rectified: boolean;
    maxShiftPixels: number;
    shiftMin: number;
    shiftMax: number;
    shiftMeanAbs: number;
    integrationMethod: string;
    pointCount: number;
    intensityMin: number;
    intensityMax: number;
    intensityMean: number;
    points: SpectrumPoint[];
    algorithmVersion: string;
    summaryMessage: string;
    details: Record<string, unknown> | null;
    createdAt: string;
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
