import http from "./http";

export interface StreamInfo {

    cameraId: string;

    status: string;

    hlsUrl?: string;

}


export async function startStream(
    cameraId: string
): Promise<StreamInfo> {

    const response =
        await http.post(
            `/streams/${cameraId}/start`
        );

    return response.data;

}


export async function stopStream(
    cameraId: string
) {

    await http.post(
        `/streams/${cameraId}/stop`
    );

}


export async function getStream(
    cameraId: string
): Promise<StreamInfo> {

    const response =
        await http.get(
            `/streams/${cameraId}`
        );

    return response.data;

}
