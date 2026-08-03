import http from "./http";

export const findAllCameras = () =>
    http.get("/cameras");

export const findCamera = (id: string) =>
    http.get(`/cameras/${id}`);
