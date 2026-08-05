import http from "@/api/http";

import type {
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
            `/v1/recordings/${recordingId}/playback`
        );

    return response.data;
}
