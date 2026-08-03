import http from "./http";
import type { Camera } from "../types/camera";

export async function findAllCameras(): Promise<Camera[]> {

    const response = await http.get<Camera[]>("/cameras");

    return response.data;

}

export async function findCamera(id: string): Promise<Camera> {

    const response = await http.get<Camera>(`/cameras/${id}`);

    return response.data;

}
