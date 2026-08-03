import { defineStore } from "pinia";
import { ref } from "vue";

import type { Camera } from "../types/camera";
import { findAllCameras } from "../api/cameraApi";

export const useCameraStore = defineStore("camera", () => {

    const cameras = ref<Camera[]>([]);

    const loading = ref(false);

    async function load() {

        loading.value = true;

        try {

            cameras.value = await findAllCameras();

        } finally {

            loading.value = false;

        }

    }

    return {

        cameras,

        loading,

        load

    };

});
