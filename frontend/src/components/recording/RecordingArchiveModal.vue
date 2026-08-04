<script setup lang="ts">
import {
  computed,
  onMounted,
  ref
} from "vue";

import type {Camera} from "@/types/Сamera";
import type {
  Recording,
  RecordingDate, RecordingStatus
} from "@/types/Recording";

import {
  findRecordingDates,
  findRecordingsByDate
} from "@/api/recordingApi";

const props = defineProps<{
  camera: Camera;
}>();

const emit = defineEmits<{
  close: [];
}>();

const dates = ref<RecordingDate[]>([]);
const recordings = ref<Recording[]>([]);

const selectedDate = ref<string | null>(null);
const selectedRecording = ref<Recording | null>(null);

const datesLoading = ref(false);
const recordingsLoading = ref(false);
const errorMessage = ref<string | null>(null);

const playbackBaseUrl =
    import.meta.env.VITE_RECORDING_URL ??
    `${window.location.protocol}//${window.location.hostname}:8080`;

const playbackUrl = computed(() => {

  const url =
      selectedRecording.value?.playbackUrl;

  if (!url) {
    return undefined;
  }

  return url.startsWith("http")
      ? url
      : `${playbackBaseUrl}${url}`;
});

async function loadDates(): Promise<void> {

  datesLoading.value = true;
  errorMessage.value = null;

  try {

    dates.value =
        await findRecordingDates(
            props.camera.id
        );

  } catch (error) {

    console.error(
        "Unable to load recording dates",
        error
    );

    errorMessage.value =
        "Unable to load archive dates.";

  } finally {

    datesLoading.value = false;

  }
}

async function selectDate(
    date: string
): Promise<void> {

  selectedDate.value = date;
  selectedRecording.value = null;
  recordings.value = [];

  recordingsLoading.value = true;
  errorMessage.value = null;

  try {

    recordings.value =
        await findRecordingsByDate(
            props.camera.id,
            date
        );

    /*
     * Если за день одна запись,
     * сразу открываем её.
     */
    if (recordings.value.length === 1) {
      selectedRecording.value =
          recordings.value[0] ?? null;
    }

  } catch (error) {

    console.error(
        "Unable to load recordings",
        error
    );

    errorMessage.value =
        "Unable to load recordings.";

  } finally {

    recordingsLoading.value = false;

  }
}

function formatTime(
    value: string
): string {

  return new Intl.DateTimeFormat(
      undefined,
      {
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit"
      }
  ).format(
      new Date(value)
  );
}

function formatDuration(
    seconds: number | null
): string {

  if (seconds == null) {
    return "—";
  }

  const hours =
      Math.floor(seconds / 3600);

  const minutes =
      Math.floor((seconds % 3600) / 60);

  const remainingSeconds =
      seconds % 60;

  if (hours > 0) {

    return [
      hours,
      minutes,
      remainingSeconds
    ]
        .map(value =>
            String(value).padStart(2, "0")
        )
        .join(":");
  }

  return [
    minutes,
    remainingSeconds
  ]
      .map(value =>
          String(value).padStart(2, "0")
      )
      .join(":");
}

function formatSize(
    bytes: number | null
): string {

  if (bytes == null) {
    return "—";
  }

  if (bytes < 1024) {
    return `${bytes} B`;
  }

  const kilobytes =
      bytes / 1024;

  if (kilobytes < 1024) {
    return `${kilobytes.toFixed(1)} KB`;
  }

  const megabytes =
      kilobytes / 1024;

  if (megabytes < 1024) {
    return `${megabytes.toFixed(1)} MB`;
  }

  const gigabytes =
      megabytes / 1024;

  return `${gigabytes.toFixed(2)} GB`;
}

function formatStatus(status?: RecordingStatus | null): string {

  if (!status) {
    return "Unknown";
  }

  const labels: Record<RecordingStatus, string> = {
    RECORDING: "Recording",
    COMPLETED: "Completed",
    FAILED: "Failed",
    STOPPED: "Stopped"
  };

  return labels[status] ?? status;

}


function statusClass(
    status?: RecordingStatus | null
): string {

  return `status-${
      (status ?? "UNKNOWN").toLowerCase()
  }`;
}

function close(): void {
  emit("close");
}

onMounted(
    loadDates
);
</script>

<template>

  <div
      class="modal-backdrop"
      @click.self="close"
  >

    <section
        class="archive-modal"
        role="dialog"
        aria-modal="true"
        :aria-label="`Archive for ${camera.name}`"
    >

      <header class="modal-header">

        <div>

          <h2>Camera archive</h2>

          <div class="camera-name">
            {{ camera.name }}
          </div>

        </div>

        <button
            type="button"
            class="close-button"
            aria-label="Close archive"
            @click="close"
        >
          ×
        </button>

      </header>

      <div
          v-if="errorMessage"
          class="error-message"
      >
        {{ errorMessage }}
      </div>

      <div
          v-if="datesLoading"
          class="loading"
      >
        Loading archive dates...
      </div>

      <div
          v-else-if="dates.length === 0"
          class="empty"
      >
        No recordings found.
      </div>

      <div
          v-else
          class="archive-layout"
      >

        <aside class="dates-panel">

          <h3>Available dates</h3>

          <button
              v-for="item in dates"
              :key="item.date"
              type="button"
              class="date-button"
              :class="{
                selected:
                    selectedDate === item.date
              }"
              @click="selectDate(item.date)"
          >

            <span>
              {{ item.date }}
            </span>

            <span class="recording-count">
              {{ item.recordingsCount }}
            </span>

          </button>

        </aside>

        <main class="recordings-panel">

          <div
              v-if="!selectedDate"
              class="empty"
          >
            Select a date.
          </div>

          <div
              v-else-if="recordingsLoading"
              class="loading"
          >
            Loading recordings...
          </div>

          <div
              v-else-if="recordings.length === 0"
              class="empty"
          >
            No recordings for this date.
          </div>

          <template v-else>

            <div class="recording-list">

              <button
                  v-for="recording in recordings"
                  :key="recording.id"
                  type="button"
                  class="recording-row"
                  :class="{selected:selectedRecording?.id === recording.id}"
                  @click="selectedRecording = recording"
              >

                <div class="recording-time">

                  <strong>
                    {{ formatTime(recording.startedAt) }}
                  </strong>

                  <span>
        –
        {{
                      recording.finishedAt
                          ? formatTime(recording.finishedAt)
                          : "Now"
                    }}
      </span>

                </div>

                <div class="recording-duration">

      <span class="field-label">
        Duration
      </span>

                  <span>
        {{ formatDuration(recording.durationSeconds) }}
      </span>

                </div>

                <div class="recording-size">

      <span class="field-label">
        Size
      </span>

                  <span>
        {{ formatSize(recording.sizeBytes) }}
      </span>

                </div>

                <div
                    class="recording-status"
                    :class="statusClass(recording.status)"
                >
                  {{ formatStatus(recording.status) }}
                </div>

              </button>

            </div>

            <video
                v-if="playbackUrl"
                :key="playbackUrl"
                :src="playbackUrl"
                class="archive-player"
                controls
                autoplay
                playsinline
            />

            <div
                v-else
                class="empty"
            >
              Select a recording.
            </div>

          </template>

        </main>

      </div>

    </section>

  </div>

</template>

<style scoped>

.modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 1000;

  display: flex;
  align-items: center;
  justify-content: center;

  padding: 24px;

  background: rgba(0, 0, 0, 0.7);
}

.archive-modal {
  width: min(1100px, 100%);
  max-height: 90vh;
  overflow: auto;

  border-radius: 12px;
  background: #fff;
  color: #222;
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;

  padding: 20px 24px;
  border-bottom: 1px solid #ddd;
}

.modal-header h2 {
  margin: 0;
}

.camera-name {
  margin-top: 4px;
  color: #666;
}

.close-button {
  border: 0;
  background: transparent;

  font-size: 32px;
  cursor: pointer;
}

.archive-layout {
  display: grid;
  grid-template-columns: 240px 1fr;
  min-height: 520px;
}

.dates-panel {
  padding: 20px;
  border-right: 1px solid #ddd;
}

.dates-panel h3 {
  margin-top: 0;
}

.date-button,
.recording-button {
  display: flex;
  justify-content: space-between;

  width: 100%;
  padding: 10px 12px;
  margin-bottom: 8px;

  border: 1px solid #ddd;
  border-radius: 6px;

  background: #fff;
  cursor: pointer;
}

.date-button.selected,
.recording-button.selected {
  border-color: #3978ff;
  background: #eef4ff;
}

.recording-count {
  color: #777;
}

.recordings-panel {
  padding: 20px;
}

.recording-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;

  margin-bottom: 16px;
}

.recording-button {
  width: auto;
  margin: 0;
}

.archive-player {
  display: block;

  width: 100%;
  max-height: 600px;

  background: #000;
}

.loading,
.empty,
.error-message {
  padding: 30px;
  text-align: center;
}

.error-message {
  color: #b42318;
}

.date-button,
.recording-button {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;

  width: 100%;
  padding: 10px 12px;
  margin-bottom: 8px;

  border: 1px solid #ddd;
  border-radius: 6px;

  background: #fff;
  color: #222;

  font: inherit;
  text-align: left;
  cursor: pointer;
}

.date-button > span:first-child {
  flex: 1;
  min-width: 0;

  color: #222;
  font-weight: 500;
}

.recording-count {
  flex: 0 0 auto;

  min-width: 28px;
  padding: 2px 7px;

  border-radius: 10px;
  background: #eee;

  color: #555;
  font-size: 12px;
  text-align: center;
}

.date-button.selected {
  border-color: #3978ff;
  background: #eef4ff;
  color: #1649a5;
}

.date-button.selected > span:first-child {
  color: #1649a5;
}

@media (max-width: 760px) {
  .archive-layout {
    grid-template-columns: 1fr;
  }

  .dates-panel {
    border-right: 0;
    border-bottom: 1px solid #ddd;
  }
}
</style>
