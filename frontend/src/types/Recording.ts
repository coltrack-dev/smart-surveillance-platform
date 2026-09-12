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

export type RecordingCleanupStatus =
    | "AVAILABLE"
    | "DELETING"
    | "LOCAL_DELETED"
    | "DELETED"
    | "DELETE_FAILED";

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

    cleanupStatus: RecordingCleanupStatus;

    deletedAt: string | null;

    deletionReason: string | null;

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

export interface RecordingStoragePolicy {
    maximumLocalBytes: number;
    cleanupTargetPercent: number;
    cleanupTargetBytes: number;
    retentionDays: number;
    minimumFreePercent: number;
    emergencyFreePercent: number;
    maxRecordingsPerRun: number;
    deleteLocalOnlyEnabled: boolean;
}

export interface StorageCleanupCandidate {
    recordingId: string;
    cameraId: string;
    finishedAt: string;
    localBytes: number;
    storageType: RecordingStorageType;
    reasons: string[];
}

export interface StorageCleanupPreview {
    cleanupRequired: boolean;
    reasons: string[];
    currentRecordingBytes: number;
    maximumLocalBytes: number;
    targetRecordingBytes: number;
    bytesToFree: number;
    candidateBytes: number;
    candidateCount: number;
    enoughEligibleData: boolean;
    checkedAt: string;
    candidates: StorageCleanupCandidate[];
}

export interface StorageCleanupResultItem {
    recordingId: string;
    status: RecordingCleanupStatus;
    freedBytes: number;
    error: string | null;
}

export interface StorageCleanupRunResult {
    attemptedCount: number;
    deletedCount: number;
    failedCount: number;
    freedBytes: number;
    targetReached: boolean;
    remainingRecordingBytes: number;
    remainingFreePercent: number;
    finishedAt: string;
    results: StorageCleanupResultItem[];
}
