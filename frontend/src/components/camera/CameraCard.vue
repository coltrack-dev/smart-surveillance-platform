<script setup lang="ts">
import type { Camera } from "../../types/camera";

defineProps<{
  camera: Camera;
}>();

function startStream() {

  console.log("Start stream");

}

function stopStream() {

  console.log("Stop stream");

}

function startRecording() {

  console.log("Start recording");

}

function stopRecording() {

  console.log("Stop recording");

}
</script>

<template>

  <div class="camera-card">

    <div class="camera-header">

      <div>

        <h2>{{ camera.name }}</h2>

        <div class="camera-id">

          {{ camera.id }}

        </div>

      </div>

      <span
          class="status"
          :class="camera.status?.toLowerCase()"
      >
            {{ camera.status }}
        </span>

    </div>

    <div class="player">

      Video Player

    </div>

    <div class="buttons">

      <button
          class="primary"
          @click="startStream"
      >
        Start Stream
      </button>

      <button
          @click="stopStream"
      >
        Stop Stream
      </button>

    </div>

    <div class="buttons">

      <button
          class="danger"
          @click="startRecording"
      >
        Start Recording
      </button>

      <button
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

.player {

  height: 240px;

  background: #111827;

  color: white;

  display: flex;

  justify-content: center;

  align-items: center;

  border-radius: 8px;

  margin-bottom: 16px;

}

.buttons {

  display: flex;

  gap: 10px;

  margin-bottom: 10px;

}

button {

  flex: 1;

  padding: 10px;

  border: none;

  border-radius: 6px;

  cursor: pointer;

  background: #e5e7eb;

}

button:hover {

  opacity: .9;

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
