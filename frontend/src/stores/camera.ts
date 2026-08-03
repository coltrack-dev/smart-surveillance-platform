import { defineStore } from "pinia";
import { ref } from "vue";
import { findAllCameras } from "../api/cameraApi";

export const useCameraStore = defineStore("camera", () => {

    const cameras = ref([]);

    async function load() {

        const response = await findAllCameras();

        cameras.value = response.data;
    }

    return {
        cameras,
        load
    };

});
