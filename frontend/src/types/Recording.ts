export interface RecordingDate {
    date: string;
    recordingsCount: number;
}
export type RecordingStatus =
    | "STARTING"
    | "RECORDING"
    | "STOPPING"
    | "COMPLETED"
    | "FAILED"
    | "STOPPED";

export type RecordingStorageType =
    | "LOCAL"
    | "S3"
    | "HYBRID"
    | "MISSING";

export interface ActiveRecording {
    id: string;
    cameraId: string;
    status: RecordingStatus;
    startedAt: string | null;
    finishedAt: string | null;
    lastError: string | null;
    durationSeconds: number | null;
    sizeBytes: number | null;
}

export interface Recording {

    id: string;

    cameraId: string;

    startedAt: string;

    finishedAt: string | null;

    durationSeconds: number | null;

    sizeBytes: number | null;

    segmentsCount: number | null;

    width: number | null;

    height: number | null;

    fps: number | null;

    codec: string | null;

    status: RecordingStatus;

    reason: string | null;

    protectedFromDeletion: boolean;

    storageType: RecordingStorageType;

    playbackUrl: string;

    downloadUrl: string;
}

export interface RecordingPage {
    content: Recording[];
    page: number;
    size: number;
    totalElements: number;
    totalPages: number;
}

export interface RecordingStorageStatus {
    totalBytes: number;
    usableBytes: number;
    usedBytes: number;
    recordingBytes: number;
    catalogedRecordingBytes: number;
    sizeDiscrepancyBytes: number;
    recordingCount: number;
    unprotectedRecordingCount: number;
    protectedRecordingCount: number;
    usedPercent: number;
    freePercent: number;
    status: "HEALTHY" | "WARNING" | "CRITICAL";
    warningThresholdPercent: number;
    criticalThresholdPercent: number;
    checkedAt: string;
}
