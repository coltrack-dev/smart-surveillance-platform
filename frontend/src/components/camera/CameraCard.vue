<script setup lang="ts">
import {
  computed,
  ref
} from "vue";

import type { Camera } from "@/types/Сamera.ts";

import CameraPlayer from "@/components/camera/CameraPlayer.vue";
import RecordingArchiveModal
  from "@/components/recording/RecordingArchiveModal.vue";
import RealtimeAnalyticsControl
  from "@/components/camera/RealtimeAnalyticsControl.vue";

import { useStreamStore } from "@/stores/streamStore";

import {
  startStream as apiStartStream,
  stopStream as apiStopStream
} from "@/api/streamApi";

import {
  startRecording as apiStartRecording,
  stopRecording as apiStopRecording
} from "@/api/recordingApi";

import { HLS_URL } from "@/config";

const props = defineProps<{
  camera: Camera;
}>();

const streamLoading = ref(false);
const recordingLoading = ref(false);
const archiveOpen = ref(false);

const streamStore = useStreamStore();

const hlsUrl = computed(() => {
  const stream = streamStore.getStream(
      props.camera.id
  );

  if (!stream?.hlsUrl) {
    return undefined;
  }

  return stream.hlsUrl.startsWith("http")
      ? stream.hlsUrl
      : `${HLS_URL}${stream.hlsUrl}`;
});

const streamConnecting = computed(() => {
  return streamStore.isStarting(
      props.camera.id
  );
});

async function startStream(): Promise<void> {
  try {
    streamLoading.value = true;

    streamStore.setStarting(
        props.camera.id,
        true
    );

    await apiStartStream(
        props.camera.id
    );
  } catch (error) {
    console.error(
        "Unable to start stream",
        error
    );

    streamStore.setStarting(
        props.camera.id,
        false
    );
  } finally {
    streamLoading.value = false;
  }
}

async function stopStream(): Promise<void> {
  try {
    streamLoading.value = true;

    await apiStopStream(
        props.camera.id
    );
  } catch (error) {
    console.error(
        "Unable to stop stream",
        error
    );
  } finally {
    streamLoading.value = false;
  }
}

async function startRecording(): Promise<void> {
  try {
    recordingLoading.value = true;

    await apiStartRecording(
        props.camera.id
    );
  } catch (error) {
    console.error(
        "Unable to start recording",
        error
    );
  } finally {
    recordingLoading.value = false;
  }
}

async function stopRecording(): Promise<void> {
  try {
    recordingLoading.value = true;

    await apiStopRecording(
        props.camera.id
    );
  } catch (error) {
    console.error(
        "Unable to stop recording",
        error
    );
  } finally {
    recordingLoading.value = false;
  }
}

function openArchive(): void {
  archiveOpen.value = true;
}

function closeArchive(): void {
  archiveOpen.value = false;
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

    <CameraPlayer
        :url="hlsUrl"
        :connecting="streamConnecting"
    />

    <div class="buttons">
      <button
          type="button"
          class="primary"
          :disabled="streamLoading"
          @click="startStream"
      >
        Start Stream
      </button>

      <button
          type="button"
          :disabled="streamLoading"
          @click="stopStream"
      >
        Stop Stream
      </button>
    </div>

    <div class="buttons">
      <button
          type="button"
          class="danger"
          :disabled="recordingLoading"
          @click="startRecording"
      >
        Start Recording
      </button>

      <button
          type="button"
          :disabled="recordingLoading"
          @click="stopRecording"
      >
        Stop Recording
      </button>
    </div>

    <div class="buttons">
      <button
          type="button"
          class="archive-button"
          @click="openArchive"
      >
        Archive
      </button>
    </div>

    <RealtimeAnalyticsControl
        :camera-id="camera.id"
    />
  </div>

  <Teleport to="body">
    <RecordingArchiveModal
        v-if="archiveOpen"
        :camera="camera"
        @close="closeArchive"
    />
  </Teleport>
</template>

<style scoped>
.camera-card {
  padding: 18px;
  background: white;
  border: 1px solid #ddd;
  border-radius: 12px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
}

.camera-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.camera-header h2 {
  margin: 0;
  font-size: 18px;
}

.camera-id {
  margin-top: 4px;
  color: gray;
  font-size: 12px;
  word-break: break-all;
}

.status {
  padding: 6px 12px;
  color: white;
  font-size: 12px;
  font-weight: bold;
  border-radius: 20px;
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

.status.error {
  background: #dc2626;
}

.buttons {
  display: flex;
  gap: 10px;
  margin-top: 10px;
}

button {
  flex: 1;
  padding: 10px;
  cursor: pointer;
  border: none;
  border-radius: 6px;
}

button:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

.primary {
  color: white;
  background: #2563eb;
}

.danger {
  color: white;
  background: #dc2626;
}

.archive-button {
  color: #1e293b;
  background: #e2e8f0;
}
</style>
