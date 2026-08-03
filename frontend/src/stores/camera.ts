import { defineStore } from "pinia";
import { ref } from "vue";

import type { Camera } from "@/types/Сamera";
import { findAllCameras } from "@/api/cameraApi";

export const useCameraStore = defineStore("camera", () => {

    const cameras = ref<Camera[]>([]);

    const loading = ref(false);

    const totalPages = ref(0);

    const currentPage = ref(0);

    async function load(
        page = 0,
        size = 20
    ): Promise<void> {

        loading.value = true;

        try {

            const result = await findAllCameras(
                page,
                size
            );

            cameras.value = result.content;

            totalPages.value = result.totalPages;

            currentPage.value = result.number;

        } finally {

            loading.value = false;

        }

    }

    /**
     * Обновить камеру по событию WebSocket.
     */
    function updateCamera(
        camera: Camera
    ): void {

        const existing = cameras.value.find(
            c => c.id === camera.id
        );

        if (!existing) {
            return;
        }

        Object.assign(
            existing,
            camera
        );

    }

    return {

        cameras,

        loading,

        totalPages,

        currentPage,

        load,

        updateCamera

    };

});
