import http from "./http";
import type { Camera, CameraPage } from "@/types/Сamera.ts";


/**
 * Получить страницу камер.
 */
export async function findAllCameras(
    page = 0,
    size = 20
): Promise<CameraPage> {

    const response =
        await http.get<CameraPage>(
            "/cameras",
            {
                params: {
                    page,
                    size
                }
            }
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
