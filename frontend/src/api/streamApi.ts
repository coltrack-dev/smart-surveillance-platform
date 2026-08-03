import http from "./http";

export const startStream = (id: string) =>
    http.post(`/streams/${id}/start`);

export const stopStream = (id: string) =>
    http.post(`/streams/${id}/stop`);
