import api from './axios';

export interface Camera {
    id: string;
    name: string;
    rtspUrl: string;
    status: string;
}


export async function getCameras() {

    const response = await api.get<Camera[]>('/cameras');

    return response.data;
}


export async function startStream(cameraId: string) {

    return api.post(
        `/streams/${cameraId}/start`
    );
}


export async function stopStream(cameraId: string) {

    return api.post(
        `/streams/${cameraId}/stop`
    );
}
