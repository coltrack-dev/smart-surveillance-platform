import { defineStore } from "pinia";
import { ref } from "vue";

import type { Amera } from "../types/Сamera";
import { findAllCameras } from "../api/cameraApi";

export const useCameraStore = defineStore("camera", () => {

    const cameras = ref<Amera[]>([]);

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
