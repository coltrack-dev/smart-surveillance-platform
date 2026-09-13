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

export interface S3StorageStatus {
    enabled: boolean;
    bucket: string | null;
    prefix: string;
    usedBytes: number;
    maximumBytes: number;
    targetBytes: number;
    remainingQuotaBytes: number;
    activeObjectCount: number;
    recordingCount: number;
    protectedBytes: number;
    usedPercent: number;
    cleanupRequired: boolean;
    checkedAt: string;
}

export interface S3StoragePolicy {
    maximumBytes: number;
    cleanupTargetPercent: number;
    cleanupTargetBytes: number;
    retentionDays: number;
    maxRecordingsPerRun: number;
    deletionEnabled: boolean;
    deleteHybridEnabled: boolean;
    requireVerifiedBeforeDeletion: boolean;
}

export interface S3CleanupCandidate {
    recordingId: string;
    cameraId: string;
    finishedAt: string;
    s3Bytes: number;
    objectCount: number;
    storageType: RecordingStorageType;
    reasons: string[];
}

export interface S3CleanupPreview {
    cleanupRequired: boolean;
    reasons: string[];
    currentBytes: number;
    maximumBytes: number;
    targetBytes: number;
    bytesToFree: number;
    candidateBytes: number;
    candidateCount: number;
    enoughEligibleData: boolean;
    checkedAt: string;
    candidates: S3CleanupCandidate[];
}

export interface S3CleanupRunResult {
    attemptedCount: number;
    deletedCount: number;
    failedCount: number;
    freedBytes: number;
    targetReached: boolean;
    remainingBytes: number;
    finishedAt: string;
    results: Array<{
        recordingId: string;
        status: "AVAILABLE" | "DELETING" | "DELETED" | "DELETE_FAILED";
        freedBytes: number;
        error: string | null;
    }>;
}

export interface S3ReconciliationProblem {
    type: "MISSING" | "SIZE_MISMATCH" | "ERROR" | "ORPHAN" | "DELETE_INCOMPLETE";
    recordingId: string | null;
    s3Key: string;
    catalogedBytes: number | null;
    actualBytes: number | null;
    details: string | null;
    detectedAt: string | null;
}

export interface S3ReconciliationResult {
    status: "NEVER_RUN" | "COMPLETED" | "PARTIAL";
    databaseObjects: number;
    listedObjects: number;
    verifiedObjects: number;
    missingObjects: number;
    sizeMismatchObjects: number;
    verificationErrors: number;
    orphanObjects: number;
    deleteIncompleteObjects: number;
    catalogedBytes: number;
    actualBytes: number;
    checkedAt: string | null;
    problems: S3ReconciliationProblem[];
}
