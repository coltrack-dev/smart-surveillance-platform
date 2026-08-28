import http from "@/api/http";

import type {
    ActiveRecording,
    Recording,
    RecordingDate
} from "@/types/Recording";

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
    const response = await http.get<ActiveRecording | null>(
        `/recordings/${cameraId}`
    );

    return response.data || null;
}
