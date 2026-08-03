import http from "./http";

export const startRecording = (id: string) =>
    http.post(`/recordings/${id}/start`);

export const stopRecording = (id: string) =>
    http.post(`/recordings/${id}/stop`);
