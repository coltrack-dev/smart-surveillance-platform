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

export async function findStream(
    id: string
): Promise<StreamInfo | null> {
    try {
        const response = await http.get<StreamInfo>(
            `/streams/${id}`
        );
        return response.data;
    } catch (error: unknown) {
        if (
            typeof error === "object"
            && error !== null
            && "response" in error
            && (error as { response?: { status?: number } }).response?.status === 404
        ) {
            return null;
        }
        throw error;
    }
}
