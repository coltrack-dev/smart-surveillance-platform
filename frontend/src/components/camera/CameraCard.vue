<script setup lang="ts">
import { ref } from "vue";

import type { Camera } from "@/types/Сamera.ts";
import CameraPlayer from "@/components/camera/CameraPlayer.vue";
import { computed } from "vue";
import { useStreamStore } from "@/stores/streamStore";

import {
  startStream as apiStartStream,
  stopStream as apiStopStream
} from "@/api/streamApi";

const props = defineProps<{
  camera: Camera;
}>();

// Состояние операций
const streamLoading = ref(false);
const recordingLoading = ref(false);

const streamStore = useStreamStore();

// URL Gateway, через который доступен HLS
const gatewayUrl =
    import.meta.env.VITE_API_URL || "http://localhost:8080";

const hlsBaseUrl =
    import.meta.env.VITE_HLS_URL || "http://localhost:8080";

const hlsUrl = computed(() => {

  const stream =
      streamStore.getStream(
          props.camera.id
      );

  if (!stream?.hlsUrl)
    return undefined;

  return stream.hlsUrl.startsWith("http")
      ? stream.hlsUrl
      : `${hlsBaseUrl}${stream.hlsUrl}`;

});

// Запуск видеопотока
async function startStream() {

  try {

    streamLoading.value = true;

    const stream =
        await apiStartStream(
            props.camera.id
        );

    console.log(
        "STREAM RESPONSE",
        stream
    );

    /*
     * Backend возвращает:
     *
     * {
     *   cameraId: "...",
     *   status: "STARTING",
     *   hlsUrl: null,
     *   startedAt: null
     * }
     *
     * HLS URL НЕ устанавливаем здесь.
     *
     * Поток еще не готов.
     * Когда FFmpeg создаст index.m3u8,
     * backend отправит StreamStartedEvent
     * через WebSocket.
     *
     * WebSocket обновит Pinia store,
     * после чего CameraPlayer получит URL.
     */

  } catch (e) {

    console.error(
        "Unable to start stream",
        e
    );

  } finally {

    streamLoading.value = false;

  }
}

// Остановка видеопотока
async function stopStream() {

  try {

    streamLoading.value = true;

    await apiStopStream(
        props.camera.id
    );

    // Убираем видео после остановки
    hlsUrl.value = undefined;

  } catch (e) {

    console.error(
        "Unable to stop stream",
        e
    );

  } finally {

    streamLoading.value = false;

  }

}

// Пока заглушки.
// Подключим к recordingApi.ts следующим шагом.
function startRecording() {

  console.log(
      "Start recording",
      props.camera.id
  );

}

function stopRecording() {

  console.log(
      "Stop recording",
      props.camera.id
  );

}
</script>

<template>
  <div class="camera-card">

    <div class="camera-header">

      <div>
        <h2>
          {{ camera.name }}
        </h2>

        <div class="camera-id">
          {{ camera.id }}
        </div>
      </div>

      <span
          class="status"
          :class="camera.status.toLowerCase()"
      >
        {{ camera.status }}
      </span>

    </div>

    <!-- HLS player -->
    <CameraPlayer
        :url="hlsUrl"
    />

    <div class="buttons">

      <button
          class="primary"
          :disabled="streamLoading"
          @click="startStream"
      >
        Start Stream
      </button>

      <button
          :disabled="streamLoading"
          @click="stopStream"
      >
        Stop Stream
      </button>

    </div>

    <div class="buttons">

      <button
          class="danger"
          :disabled="recordingLoading"
          @click="startRecording"
      >
        Start Recording
      </button>

      <button
          :disabled="recordingLoading"
          @click="stopRecording"
      >
        Stop Recording
      </button>

    </div>

  </div>
</template>

<style scoped>
.camera-card {
  background: white;
  border-radius: 12px;
  border: 1px solid #ddd;
  padding: 18px;
  box-shadow: 0 2px 8px rgba(0,0,0,.08);
}

.camera-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.camera-header h2 {
  margin: 0;
  font-size: 18px;
}

.camera-id {
  color: gray;
  font-size: 12px;
  margin-top: 4px;
  word-break: break-all;
}

.status {
  padding: 6px 12px;
  border-radius: 20px;
  font-size: 12px;
  font-weight: bold;
  color: white;
}

.status.online {
  background: #16a34a;
}

.status.offline {
  background: #dc2626;
}

.status.recording {
  background: #ea580c;
}

.buttons {
  display: flex;
  gap: 10px;
  margin-top: 10px;
}

button {
  flex: 1;
  padding: 10px;
  border: none;
  border-radius: 6px;
  cursor: pointer;
}

button:disabled {
  opacity: .5;
  cursor: not-allowed;
}

.primary {
  background: #2563eb;
  color: white;
}

.danger {
  background: #dc2626;
  color: white;
}
</style>
