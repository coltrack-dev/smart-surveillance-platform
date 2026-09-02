<script setup lang="ts">
import {computed, onBeforeUnmount, onMounted, ref} from "vue";

import type {Camera} from "@/types/Сamera.ts";
import type {ActiveRecording} from "@/types/Recording";
import CameraPlayer from "@/components/camera/CameraPlayer.vue";
import RecordingArchiveModal from "@/components/recording/RecordingArchiveModal.vue";
import RealtimeAnalyticsControl from "@/components/camera/RealtimeAnalyticsControl.vue";
import {useStreamStore} from "@/stores/streamStore";
import {
  findStream,
  startStream as apiStartStream,
  stopStream as apiStopStream
} from "@/api/streamApi";
import {
  findActiveRecording,
  startRecording as apiStartRecording,
  stopRecording as apiStopRecording
} from "@/api/recordingApi";
import {HLS_URL} from "@/config";

const props = defineProps<{ camera: Camera }>();
const streamLoading = ref(false);
const recordingLoading = ref(false);
const recording = ref<ActiveRecording | null>(null);
const operationMessage = ref<string | null>(null);
const archiveOpen = ref(false);
const now = ref(Date.now());
const streamStore = useStreamStore();
let recordingPollTimer: number | undefined;
let clockTimer: number | undefined;

const streamInfo = computed(() => streamStore.getStream(props.camera.id));
const streamRunning = computed(() =>
    ["RUNNING", "RECONNECTING"].includes(streamInfo.value?.status ?? "")
);
const streamConnecting = computed(() =>
    streamStore.isStarting(props.camera.id)
    || ["STARTING", "RECONNECTING"].includes(streamInfo.value?.status ?? "")
);
const recordingActive = computed(() =>
    ["STARTING", "RECORDING", "STOPPING"].includes(recording.value?.status ?? "")
);
const hlsUrl = computed(() => {
  const url = streamInfo.value?.hlsUrl;
  if (!url) return undefined;
  return url.startsWith("http") ? url : `${HLS_URL}${url}`;
});
const streamStatusText = computed(() => {
  if (streamConnecting.value) return "Starting stream...";
  if (streamInfo.value?.status === "RECONNECTING") return "Reconnecting...";
  if (streamRunning.value) return `Running${formatElapsed(streamInfo.value?.startedAt)}`;
  return "Stopped";
});
const recordingStatusText = computed(() => {
  const current = recording.value;
  switch (current?.status) {
    case "STARTING":
      return "Starting recording...";
    case "RECORDING":
      return `Recording${formatElapsed(current.startedAt)}`;
    case "STOPPING":
      return "Finishing recording...";
    case "FAILED":
      return current.lastError || "Recording failed";
    default:
      return "Not recording";
  }
});
const safeRtspUrl = computed(() => maskRtspPassword(props.camera.rtspUrl));

function formatElapsed(startedAt?: string | null): string {
  if (!startedAt) return "";
  const seconds = Math.max(0, Math.floor((now.value - new Date(startedAt).getTime()) / 1000));
  const hours = Math.floor(seconds / 3600).toString().padStart(2, "0");
  const minutes = Math.floor((seconds % 3600) / 60).toString().padStart(2, "0");
  const rest = (seconds % 60).toString().padStart(2, "0");
  return ` · ${hours}:${minutes}:${rest}`;
}

function maskRtspPassword(url: string): string {
  return url
      .replace(/(rtsp:\/\/[^:/@]+:)[^@]+@/i, "$1••••••••@")
      .replace(/(_password=)[^_/?]+/i, "$1••••••••");
}

async function refreshRecording(): Promise<void> {
  try {
    recording.value = await findActiveRecording(props.camera.id);
  } catch (error) {
    console.error("Unable to load recording status", error);
  }
}

async function refreshStream(): Promise<void> {
  try {
    const stream = await findStream(props.camera.id);
    if (!stream) return;
    streamStore.updateStream({
      cameraId: stream.cameraId,
      status: stream.status,
      hlsUrl: stream.hlsUrl ?? null,
      error: stream.lastError ?? null,
      startedAt: stream.startedAt ?? null
    });
  } catch (error) {
    console.error("Unable to load stream status", error);
  }
}

async function startStream(): Promise<void> {
  operationMessage.value = null;
  try {
    streamLoading.value = true;
    streamStore.setStarting(props.camera.id, true);
    await apiStartStream(props.camera.id);
    operationMessage.value = "Stream started without recording.";
  } catch (error) {
    console.error("Unable to start stream", error);
    streamStore.setStarting(props.camera.id, false);
    operationMessage.value = "Unable to start stream.";
  } finally {
    streamLoading.value = false;
  }
}

async function stopStream(): Promise<void> {
  if (recordingActive.value && !window.confirm(
      "Recording is active. Stopping the stream will also finish the recording. Continue?"
  )) return;

  const wasRecording = recordingActive.value;
  operationMessage.value = null;
  try {
    streamLoading.value = true;
    if (wasRecording) {
      await apiStopRecording(props.camera.id);
      if (recording.value) recording.value.status = "STOPPING";
    }
    await apiStopStream(props.camera.id);
    operationMessage.value = wasRecording
        ? "Stream stopped and recording is being finalized."
        : "Stream stopped.";
  } catch (error) {
    console.error("Unable to stop stream", error);
    operationMessage.value = "Unable to stop stream.";
  } finally {
    streamLoading.value = false;
  }
}

async function startRecording(): Promise<void> {
  const streamWasRunning = streamRunning.value;
  operationMessage.value = null;
  try {
    recordingLoading.value = true;
    if (!streamWasRunning) streamStore.setStarting(props.camera.id, true);
    recording.value = await apiStartRecording(props.camera.id);
    operationMessage.value = streamWasRunning
        ? "Recording started. The stream remains active."
        : "The stream was started automatically for recording.";
  } catch (error) {
    console.error("Unable to start recording", error);
    streamStore.setStarting(props.camera.id, false);
    operationMessage.value = "Unable to start recording.";
  } finally {
    recordingLoading.value = false;
  }
}

async function stopRecording(): Promise<void> {
  operationMessage.value = null;
  try {
    recordingLoading.value = true;
    await apiStopRecording(props.camera.id);
    if (recording.value) recording.value.status = "STOPPING";
    operationMessage.value = "Recording is being finalized. The stream remains active.";
  } catch (error) {
    console.error("Unable to stop recording", error);
    operationMessage.value = "Unable to stop recording.";
  } finally {
    recordingLoading.value = false;
  }
}

async function copyRtspUrl(): Promise<void> {
  await navigator.clipboard.writeText(safeRtspUrl.value);
  operationMessage.value = "RTSP URL copied with the password hidden.";
}

onMounted(() => {
  void refreshStream();
  void refreshRecording();
  recordingPollTimer = window.setInterval(() => void refreshRecording(), 3000);
  clockTimer = window.setInterval(() => {
    now.value = Date.now();
  }, 1000);
});
onBeforeUnmount(() => {
  if (recordingPollTimer !== undefined) window.clearInterval(recordingPollTimer);
  if (clockTimer !== undefined) window.clearInterval(clockTimer);
});
</script>

<template>
  <article class="camera-card">
    <header class="camera-header">
      <div><h2>{{ camera.name }}</h2>
        <div class="camera-id">{{ camera.id }}</div>
      </div>
      <span class="status" :class="camera.status.toLowerCase()">{{ camera.status }}</span>
    </header>

    <CameraPlayer :url="hlsUrl" :connecting="streamConnecting"/>

    <section class="control-row">
      <div><strong>Stream</strong>
        <div class="control-status" :class="{ active: streamRunning }">{{ streamStatusText }}</div>
      </div>
      <button v-if="!streamRunning" type="button" class="primary" :disabled="streamLoading || streamConnecting"
              @click="startStream">Start Stream
      </button>
      <button v-else type="button" :disabled="streamLoading" @click="stopStream">Stop Stream</button>
    </section>

    <section class="control-row">
      <div><strong>Recording</strong>
        <div class="control-status" :class="{ recording: recordingActive }">{{ recordingStatusText }}</div>
      </div>
      <div class="row-actions">
        <button v-if="!recordingActive" type="button" class="record-button" :disabled="recordingLoading"
                @click="startRecording">Start Recording
        </button>
        <button v-else type="button" :disabled="recordingLoading || recording?.status === 'STOPPING'"
                @click="stopRecording">Stop Recording
        </button>
        <button type="button" class="archive-button" @click="archiveOpen = true">Archive</button>
      </div>
    </section>

    <RealtimeAnalyticsControl :camera-id="camera.id" :stream-active="streamRunning"/>

    <details class="connection-details">
      <summary>Connection and RTSP URL</summary>
      <div class="rtsp-row"><code>{{ safeRtspUrl }}</code>
        <button type="button" @click="copyRtspUrl">Copy</button>
      </div>
    </details>
    <small v-if="operationMessage" class="operation-message">{{ operationMessage }}</small>
  </article>

  <Teleport to="body">
    <RecordingArchiveModal v-if="archiveOpen" :camera="camera" @close="archiveOpen = false"/>
  </Teleport>
</template>

<style scoped>
.camera-card {
  padding: 18px;
  background: white;
  border: 1px solid #ddd;
  border-radius: 12px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, .08);
}

.camera-header, .control-row, .row-actions, .rtsp-row {
  display: flex;
  align-items: center;
}

.camera-header {
  justify-content: space-between;
  margin-bottom: 16px;
}

.camera-header h2 {
  margin: 0;
  font-size: 18px;
}

.camera-id {
  margin-top: 4px;
  color: #64748b;
  font-size: 12px;
  word-break: break-all;
}

.status {
  padding: 6px 12px;
  color: white;
  font-size: 12px;
  font-weight: 700;
  border-radius: 20px;
}

.status.online {
  background: #16a34a;
}

.status.offline, .status.error {
  background: #dc2626;
}

.status.recording {
  background: #ea580c;
}

.control-row {
  justify-content: space-between;
  gap: 12px;
  padding: 13px 0;
  border-bottom: 1px solid #e5e7eb;
}

.control-status {
  margin-top: 4px;
  color: #64748b;
  font-size: 12px;
}

.control-status.active {
  color: #16a34a;
}

.control-status.recording {
  color: #dc2626;
}

.row-actions {
  gap: 8px;
}

button {
  padding: 9px 12px;
  cursor: pointer;
  border: 0;
  border-radius: 6px;
  white-space: nowrap;
}

button:disabled {
  cursor: not-allowed;
  opacity: .5;
}

.primary {
  color: white;
  background: #2563eb;
}

.record-button {
  color: white;
  background: #dc2626;
}

.archive-button {
  color: #1e293b;
  background: #e2e8f0;
}

.connection-details {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid #e5e7eb;
}

.connection-details summary {
  color: #64748b;
  font-size: 12px;
  cursor: pointer;
}

.rtsp-row {
  gap: 8px;
  margin-top: 9px;
}

.rtsp-row code {
  min-width: 0;
  flex: 1;
  padding: 8px;
  overflow: hidden;
  color: #334155;
  background: #f1f5f9;
  border-radius: 6px;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.operation-message {
  display: block;
  margin-top: 9px;
  color: #64748b;
}

@media (max-width: 560px) {
  .control-row {
    align-items: stretch;
    flex-direction: column;
  }

  .row-actions {
    width: 100%;
  }

  .row-actions button, .control-row > button {
    flex: 1;
  }

  .rtsp-row {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
