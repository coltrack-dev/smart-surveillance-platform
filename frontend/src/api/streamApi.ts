import http from "./http";

export interface StreamInfo {
    cameraId: string;
    status: string;
    hlsUrl?: string;
    startedAt?: string;
    reconnectCount?: number;
    lastError?: string | null;
}

export async function startStream(
    id: string
): Promise<StreamInfo> {

    const response =
        await http.post<StreamInfo>(
            `/streams/${id}/start`
        );

    return response.data;
}


export async function stopStream(
    id: string
): Promise<void> {

    await http.post(
        `/streams/${id}/stop`
    );

}
