<script setup lang="ts">
import { onMounted } from "vue";

import CameraCard from "../components/camera/CameraCard.vue";
import { useCameraStore } from "../stores/camera";
import { connectStreamSocket } from "@/ws/streamSocket";
import { useStreamStore } from "@/stores/streamStore";

const store = useCameraStore();
const streamStore = useStreamStore();

onMounted(async () => {

  await store.load();

  connectStreamSocket(
      event => {

        streamStore.updateStream(
            event
        );

      }
  );

});
</script>

<template>

  <div class="dashboard">

    <div class="dashboard-header">

      <h1>Surveillance Dashboard</h1>

      <div class="camera-count">

        Cameras: {{ store.cameras.length }}

      </div>

    </div>

    <div
        v-if="store.loading"
        class="loading"
    >
      Loading cameras...
    </div>

    <div
        v-else-if="store.cameras.length === 0"
        class="empty"
    >
      No cameras found
    </div>

    <div
        v-else
        class="camera-grid"
    >

      <CameraCard
          v-for="camera in store.cameras"
          :key="camera.id"
          :camera="camera"
      />

    </div>

  </div>

</template>

<style scoped>

.dashboard {

  padding: 24px;

}

.dashboard-header {

  display: flex;

  justify-content: space-between;

  align-items: center;

  margin-bottom: 24px;

}

.dashboard-header h1 {

  margin: 0;

}

.camera-count {

  color: #666;

}

.camera-grid {

  display: grid;

  grid-template-columns: repeat(auto-fill, minmax(420px, 1fr));

  gap: 20px;

}

.loading,
.empty {

  text-align: center;

  padding: 60px;

  color: gray;

}

</style>
