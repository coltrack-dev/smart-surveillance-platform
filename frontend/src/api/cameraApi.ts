import http from "./http";
import type { Camera } from "@/types/Сamera.ts";

/**
 * Получить список всех камер.
 */
export async function findAllCameras(): Promise<Camera[]> {

    const response =
        await http.get<Camera[]>(
            "/cameras"
        );

    return response.data;
}


/**
 * Получить камеру по id.
 */
export async function findCamera(
    id: string
): Promise<Camera> {

    const response =
        await http.get<Camera>(
            `/cameras/${id}`
        );

    return response.data;
}
