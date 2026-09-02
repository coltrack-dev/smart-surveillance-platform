import http from "@/api/http";

import type {
    ActiveRecording,
    Recording,
    RecordingDate,
    RecordingPage,
    RecordingStatus,
    RecordingStorageStatus
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
        const response = await http.get<ActiveRecording>(
            `/recordings/${cameraId}`
        );

        return response.data;
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
