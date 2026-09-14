import http from "@/api/http";

import type {
    ActiveRecording,
    Recording,
    RecordingDate,
    RecordingPage,
    RecordingStatus,
    RecordingStorageStatus,
    RecordingStoragePolicy,
    StorageCleanupPreview,
    StorageCleanupRunResult,
    S3StorageStatus,
    S3StoragePolicy,
    S3CleanupPreview,
    S3CleanupRunResult,
    S3ReconciliationProblem,
    S3ReconciliationResult
} from "@/types/Recording";

export interface RecordingSearchParameters {
    cameraId?: string;
    from?: string;
    to?: string;
    statuses?: RecordingStatus[];
    protected?: boolean;
    page?: number;
    size?: number;
}

export async function findRecordings(
    parameters: RecordingSearchParameters
): Promise<RecordingPage> {
    const response = await http.get<RecordingPage>(
        "/recordings",
        { params: parameters }
    );

    return response.data;
}

export async function setRecordingProtection(
    recordingId: string,
    protectedFromDeletion: boolean
): Promise<Recording> {
    const response = await http.patch<Recording>(
        `/recordings/${recordingId}/protection`,
        { protected: protectedFromDeletion }
    );

    return response.data;
}

export async function getRecordingStorageStatus(): Promise<RecordingStorageStatus> {
    const response = await http.get<RecordingStorageStatus>(
        "/recordings/storage"
    );

    return response.data;
}

export async function getRecordingStoragePolicy(): Promise<RecordingStoragePolicy> {
    const response = await http.get<RecordingStoragePolicy>(
        "/recordings/storage/policy"
    );
    return response.data;
}

export async function previewRecordingStorageCleanup(): Promise<StorageCleanupPreview> {
    const response = await http.post<StorageCleanupPreview>(
        "/recordings/storage/cleanup/preview",
        undefined,
        { timeout: 15000 }
    );
    return response.data;
}

export async function runRecordingStorageCleanup(): Promise<StorageCleanupRunResult> {
    const response = await http.post<StorageCleanupRunResult>(
        "/recordings/storage/cleanup/run",
        undefined,
        { timeout: 60000 }
    );
    return response.data;
}

export async function getS3StorageStatus(): Promise<S3StorageStatus> {
    const response = await http.get<S3StorageStatus>("/recordings/storage/s3");
    return response.data;
}

export async function getS3StoragePolicy(): Promise<S3StoragePolicy> {
    const response = await http.get<S3StoragePolicy>("/recordings/storage/s3/policy");
    return response.data;
}

export async function previewS3StorageCleanup(): Promise<S3CleanupPreview> {
    const response = await http.post<S3CleanupPreview>(
        "/recordings/storage/s3/cleanup/preview",
        undefined,
        { timeout: 15000 }
    );
    return response.data;
}

export async function runS3StorageCleanup(): Promise<S3CleanupRunResult> {
    const response = await http.post<S3CleanupRunResult>(
        "/recordings/storage/s3/cleanup/run",
        undefined,
        { timeout: 60000 }
    );
    return response.data;
}

export async function getS3Reconciliation(): Promise<S3ReconciliationResult> {
    const response = await http.get<S3ReconciliationResult>(
        "/recordings/storage/s3/reconciliation"
    );
    return response.data;
}

export async function runS3Reconciliation(): Promise<S3ReconciliationResult> {
    const response = await http.post<S3ReconciliationResult>(
        "/recordings/storage/s3/reconciliation/run",
        undefined,
        { timeout: 120000 }
    );
    return response.data;
}

export async function getS3Problems(): Promise<S3ReconciliationProblem[]> {
    const response = await http.get<S3ReconciliationProblem[]>(
        "/recordings/storage/s3/problems"
    );
    return response.data;
}

export async function acknowledgeS3Problem(s3Key: string): Promise<void> {
    await http.post("/recordings/storage/s3/problems/acknowledge", { s3Key });
}

export async function deleteS3Orphan(s3Key: string): Promise<{freedBytes: number}> {
    const response = await http.post<{freedBytes: number}>(
        "/recordings/storage/s3/problems/delete-orphan", { s3Key }, { timeout: 120000 }
    );
    return response.data;
}

export function resolveRecordingDownloadUrl(recording: Recording): string {
    if (recording.downloadUrl.startsWith("http")) {
        return recording.downloadUrl;
    }

    const configuredApiUrl = import.meta.env.VITE_API_URL as string | undefined;
    const gatewayOrigin = configuredApiUrl?.startsWith("http")
        ? new URL(configuredApiUrl).origin
        : `${window.location.protocol}//${window.location.hostname}:8080`;
    const path = recording.downloadUrl.startsWith("/")
        ? recording.downloadUrl
        : `/${recording.downloadUrl}`;

    return `${gatewayOrigin}${path}`;
}

/**
 * Получить даты, за которые у камеры имеются записи.
 */
export async function findRecordingDates(
    cameraId: string
): Promise<RecordingDate[]> {

    const response =
        await http.get<RecordingDate[]>(
            `/recordings/cameras/${cameraId}/dates`
        );

    return response.data;
}

/**
 * Получить записи камеры за выбранную дату.
 */
export async function findRecordingsByDate(
    cameraId: string,
    date: string
): Promise<Recording[]> {

    const response =
        await http.get<Recording[]>(
            `/recordings/cameras/${cameraId}`,
            {
                params: {
                    date
                }
            }
        );

    return response.data;
}

export interface RecordingPlaybackResponse {
    status: "READY" | "PREPARING" | "FAILED";
    playbackUrl: string | null;
}

export async function prepareRecordingPlayback(
    recordingId: string
): Promise<RecordingPlaybackResponse> {

    const response =
        await http.post<RecordingPlaybackResponse>(
            `/recordings/${recordingId}/playback`
        );

    return response.data;
}

export async function startRecording(
    cameraId: string
): Promise<ActiveRecording> {
    const response = await http.post<ActiveRecording>(
        `/recordings/${cameraId}/start`
    );

    return response.data;
}

export async function stopRecording(
    cameraId: string
): Promise<void> {
    await http.post(
        `/recordings/${cameraId}/stop`
    );
}

export async function findActiveRecording(
    cameraId: string
): Promise<ActiveRecording | null> {
    try {
        const response = await http.get<ActiveRecording | null>(
            `/recordings/${cameraId}`
        );

        return response.status === 204 ? null : response.data;
    } catch (error: unknown) {
        if (
            typeof error === "object"
            && error !== null
            && "response" in error
            && (error as { response?: { status?: number } })
                .response?.status === 404
        ) {
            return null;
        }

        throw error;
    }
}
