<script setup lang="ts">
import {
  computed,
  onMounted,
  onUnmounted,
  ref
} from "vue";

import type {Camera} from "@/types/Сamera";
import type {
  Recording,
  RecordingDate, RecordingStatus
} from "@/types/Recording";

import {
  prepareRecordingPlayback
} from "@/api/recordingApi";


import {
  findRecordingDates,
  findRecordingsByDate
} from "@/api/recordingApi";

import CameraPlayer from "@/components/camera/CameraPlayer.vue";
import type {AnalyticsJob, AnalyticsJobStatus} from "@/types/AnalyticsControl";
import type {AnalyticsEvent} from "@/types/AnalyticsEvent";
import {
  findAnalyticsJob,
  findAnalyticsJobEvents,
  findLatestRecordingAnalyticsJob,
  startRecordingAnalytics,
  stopRecordingAnalytics
} from "@/api/analyticsApi";

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

const playbackLoading = ref(false);
const playbackError = ref<string | null>(null);
const preparedPlaybackUrl = ref<string | null>(null);
const analyticsJob = ref<AnalyticsJob | null>(null);
const analyticsLoading = ref(false);
const analyticsStopping = ref(false);
const analyticsError = ref<string | null>(null);
const analyticsResultsOpen = ref(false);
const analyticsEvents = ref<AnalyticsEvent[]>([]);
const analyticsEventsTotal = ref(0);
const analyticsEventsLoading = ref(false);
const analyticsEventsError = ref<string | null>(null);
const failedSnapshots = ref<Set<string>>(new Set());
let analyticsPollingTimer: ReturnType<typeof setInterval> | null = null;

const activeAnalyticsStatuses = new Set<AnalyticsJobStatus>([
  "REQUESTED",
  "RUNNING",
  "RETRYING",
  "STOP_REQUESTED"
]);

const analyticsRunning = computed(() =>
    analyticsJob.value != null
    && activeAnalyticsStatuses.has(analyticsJob.value.status)
);

const analyticsProgress = computed<number | null>(() => {
  const value = analyticsJob.value?.details?.progressPercent;
  return typeof value === "number" ? Math.max(0, Math.min(100, value)) : null;
});

function numericAnalyticsDetail(name: string): number | null {
  const value = analyticsJob.value?.details?.[name];
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

const processedFrames = computed(() => numericAnalyticsDetail("processedFrames"));
const totalFrames = computed(() => numericAnalyticsDetail("totalFrames"));

const processedVideoSeconds = computed<number | null>(() => {
  const processed = processedFrames.value;
  const total = totalFrames.value;
  const duration = selectedRecording.value?.durationSeconds;
  if (processed == null || total == null || total <= 0 || duration == null) {
    return null;
  }
  return Math.min(duration, Math.round(duration * processed / total));
});

const analyticsEtaSeconds = computed<number | null>(() => {
  const progress = analyticsProgress.value;
  const startedAt = analyticsJob.value?.startedAt;
  if (progress == null || progress <= 0 || progress >= 100 || !startedAt) {
    return null;
  }
  const elapsedSeconds = Math.max(0, (Date.now() - new Date(startedAt).getTime()) / 1000);
  return Math.max(0, Math.round(elapsedSeconds * (100 - progress) / progress));
});

const canAnalyzeSelectedRecording = computed(() =>
    selectedRecording.value != null
    && selectedRecording.value.status !== "RECORDING"
    && selectedRecording.value.finishedAt != null
);

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
      const recording = recordings.value[0];
      if (recording) {
        await selectRecording(recording);
      }
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
  stopAnalyticsPolling();
  emit("close");
}

function formatConfidence(value: number | null): string {
  return value == null ? "â€”" : `${(value * 100).toFixed(1)}%`;
}

function eventDirection(event: AnalyticsEvent): string {
  const direction = event.attributes?.direction;
  return typeof direction === "string" ? direction : "â€”";
}

function getSnapshotUrl(event: AnalyticsEvent): string | undefined {
  const path = event.snapshotUrl;
  if (!path) {
    return undefined;
  }
  if (path.startsWith("http://") || path.startsWith("https://")) {
    return path;
  }
  const normalizedPath = path.startsWith("/") ? path : `/${path}`;
  const apiUrl = import.meta.env.VITE_API_URL as string | undefined;
  return apiUrl?.startsWith("http")
      ? `${new URL(apiUrl).origin}${normalizedPath}`
      : normalizedPath;
}

function snapshotAvailable(event: AnalyticsEvent): boolean {
  return Boolean(event.snapshotUrl) && !failedSnapshots.value.has(event.eventId);
}

function handleSnapshotError(event: AnalyticsEvent): void {
  failedSnapshots.value = new Set([...failedSnapshots.value, event.eventId]);
}

async function openAnalyticsResults(): Promise<void> {
  const job = analyticsJob.value;
  if (!job || job.status !== "COMPLETED") {
    return;
  }
  analyticsResultsOpen.value = true;
  analyticsEventsLoading.value = true;
  analyticsEventsError.value = null;
  analyticsEvents.value = [];
  failedSnapshots.value = new Set();
  try {
    const result = await findAnalyticsJobEvents(job.jobId);
    if (analyticsJob.value?.jobId !== job.jobId) {
      return;
    }
    analyticsEvents.value = result.content;
    analyticsEventsTotal.value = result.totalElements;
  } catch (error) {
    console.error("Unable to load analytics results", error);
    analyticsEventsError.value = "Unable to load analysis results.";
  } finally {
    analyticsEventsLoading.value = false;
  }
}

function closeAnalyticsResults(): void {
  analyticsResultsOpen.value = false;
}

function analyticsStatusLabel(status: AnalyticsJobStatus): string {
  const labels: Record<AnalyticsJobStatus, string> = {
    REQUESTED: "Waiting for worker",
    RUNNING: "Analysis in progress",
    RETRYING: "Retrying analysis",
    STOP_REQUESTED: "Stopping",
    STOPPED: "Stopped",
    COMPLETED: "Analysis completed",
    FAILED: "Analysis failed",
    REJECTED: "Analysis rejected"
  };
  return labels[status];
}

function stopAnalyticsPolling(): void {
  if (analyticsPollingTimer != null) {
    clearInterval(analyticsPollingTimer);
    analyticsPollingTimer = null;
  }
}

function startAnalyticsPolling(jobId: string): void {
  stopAnalyticsPolling();
  analyticsPollingTimer = setInterval(async () => {
    try {
      const job = await findAnalyticsJob(jobId);
      if (job.recordingId !== selectedRecording.value?.id) {
        return;
      }
      analyticsJob.value = job;
      if (!activeAnalyticsStatuses.has(job.status)) {
        stopAnalyticsPolling();
      }
    } catch (error) {
      console.error("Unable to refresh analytics job", error);
    }
  }, 3000);
}

async function loadRecordingAnalytics(recordingId: string): Promise<void> {
  stopAnalyticsPolling();
  analyticsJob.value = null;
  analyticsError.value = null;
  try {
    const job = await findLatestRecordingAnalyticsJob(recordingId);
    if (selectedRecording.value?.id !== recordingId) {
      return;
    }
    analyticsJob.value = job;
    if (job && activeAnalyticsStatuses.has(job.status)) {
      startAnalyticsPolling(job.jobId);
    }
  } catch (error) {
    console.error("Unable to load recording analytics", error);
    analyticsError.value = "Unable to load analytics status.";
  }
}

async function runRecordingAnalytics(): Promise<void> {
  const recording = selectedRecording.value;
  if (!recording || !canAnalyzeSelectedRecording.value || analyticsRunning.value) {
    return;
  }
  analyticsLoading.value = true;
  analyticsError.value = null;
  try {
    const job = await startRecordingAnalytics(recording.id, {
      cameraId: recording.cameraId,
      classes: [0],
      confidence: 0.5,
      devicePreference: "auto",
      linePosition: 0.5,
      targetFps: 10
    });
    analyticsJob.value = job;
    startAnalyticsPolling(job.jobId);
  } catch (error) {
    console.error("Unable to start recording analytics", error);
    analyticsError.value = "Unable to start recording analysis.";
  } finally {
    analyticsLoading.value = false;
  }
}

async function stopRecordingAnalysis(): Promise<void> {
  const recording = selectedRecording.value;
  if (!recording || !analyticsRunning.value || analyticsStopping.value) {
    return;
  }
  analyticsStopping.value = true;
  analyticsError.value = null;
  try {
    analyticsJob.value = await stopRecordingAnalytics(recording.id);
    startAnalyticsPolling(analyticsJob.value.jobId);
  } catch (error) {
    console.error("Unable to stop recording analytics", error);
    analyticsError.value = "Unable to stop recording analysis.";
  } finally {
    analyticsStopping.value = false;
  }
}

async function selectRecording(
    recording: Recording
): Promise<void> {

  selectedRecording.value = recording;
  analyticsResultsOpen.value = false;
  void loadRecordingAnalytics(recording.id);

  preparedPlaybackUrl.value = null;
  playbackError.value = null;
  playbackLoading.value = true;

  try {

    const result =
        await prepareRecordingPlayback(
            recording.id
        );

    if (
        result.status !== "READY"
        || !result.playbackUrl
    ) {

      throw new Error(
          "Playback is not ready"
      );
    }

    preparedPlaybackUrl.value =
        result.playbackUrl.startsWith("http")
            ? result.playbackUrl
            : `${
                window.location.protocol
            }//${
                window.location.hostname
            }:8080${
                result.playbackUrl
            }`;

  } catch (error) {

    console.error(
        "Unable to prepare playback",
        error
    );

    playbackError.value =
        "Unable to prepare recording playback.";

  } finally {

    playbackLoading.value = false;

  }
}

onMounted(
    loadDates
);

onUnmounted(stopAnalyticsPolling);
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
                  :class="{
      selected:
        selectedRecording?.id === recording.id
    }"
                  @click="selectRecording(recording)"
              >

                <div class="recording-time">

                  <strong>
                    {{ formatTime(recording.startedAt) }}
                  </strong>

                  <span>
                    – {{recording.finishedAt ? formatTime(recording.finishedAt) : "Now"}}
                  </span>

                </div>

                <div class="recording-duration">
                  <span class="field-label">Duration</span>
                  <span>{{ formatDuration(recording.durationSeconds) }}</span>
                </div>

                <div class="recording-size">
                  <span class="field-label">Size</span>
                  <span>{{ formatSize(recording.sizeBytes) }}</span>
                </div>

                <div class="recording-status" :class="statusClass(recording.status)">
                  {{ formatStatus(recording.status) }}
                </div>

              </button>

            </div>


            <div
                v-if="selectedRecording"
                class="analytics-controls"
            >
              <button
                  type="button"
                  class="analytics-button"
                  :disabled="analyticsLoading || analyticsRunning || !canAnalyzeSelectedRecording"
                  @click="runRecordingAnalytics"
              >
                {{ analyticsLoading
                    ? "Starting..."
                    : analyticsRunning
                        ? "Analysis in progress"
                        : analyticsJob?.status === "FAILED"
                            ? "Retry analysis"
                            : "Analyze recording" }}
              </button>

              <span
                  v-if="analyticsJob"
                  class="analytics-status"
                  :class="`analytics-${analyticsJob.status.toLowerCase()}`"
              >
                {{ analyticsStatusLabel(analyticsJob.status) }}
              </span>

              <button
                  v-if="analyticsRunning"
                  type="button"
                  class="analytics-stop-button"
                  :disabled="analyticsStopping || analyticsJob?.status === 'STOP_REQUESTED'"
                  @click="stopRecordingAnalysis"
              >
                {{ analyticsStopping || analyticsJob?.status === "STOP_REQUESTED"
                    ? "Stopping..."
                    : "Stop analysis" }}
              </button>

              <button
                  v-if="analyticsJob?.status === 'COMPLETED'"
                  type="button"
                  class="analytics-results-button"
                  @click="openAnalyticsResults"
              >
                View results
              </button>

              <span
                  v-if="analyticsError"
                  class="analytics-error"
              >
                {{ analyticsError }}
              </span>

              <div
                  v-if="analyticsRunning"
                  class="analytics-progress-section"
              >
                <div
                    class="analytics-progress"
                    role="progressbar"
                    :aria-valuenow="analyticsProgress ?? undefined"
                    aria-valuemin="0"
                    aria-valuemax="100"
                >
                  <div
                      class="analytics-progress-fill"
                      :class="{ indeterminate: analyticsProgress == null }"
                      :style="analyticsProgress == null ? undefined : { width: `${analyticsProgress}%` }"
                  />
                  <span>{{ analyticsProgress == null ? "Preparing..." : `${analyticsProgress}%` }}</span>
                </div>

                <div
                    v-if="processedFrames != null && totalFrames != null"
                    class="analytics-progress-details"
                >
                  <span v-if="processedVideoSeconds != null && selectedRecording?.durationSeconds != null">
                    Video: {{ formatDuration(processedVideoSeconds) }} / {{ formatDuration(selectedRecording.durationSeconds) }}
                  </span>
                  <span>Frames: {{ processedFrames }} / {{ totalFrames }}</span>
                  <span v-if="analyticsEtaSeconds != null">
                    About {{ formatDuration(analyticsEtaSeconds) }} remaining
                  </span>
                </div>
              </div>
            </div>


            <div class="archive-video-panel">

              <div
                  v-if="playbackLoading"
                  class="archive-video-empty"
              >
                Preparing playback...
              </div>

              <div
                  v-else-if="playbackError"
                  class="archive-video-empty error-message"
              >
                {{ playbackError }}
              </div>

              <CameraPlayer
                  v-else-if="preparedPlaybackUrl"
                  :key="preparedPlaybackUrl"
                  :url="preparedPlaybackUrl"
                  :connecting="false"
                  proportional
                  class="archive-player"
              />

              <div
                  v-else
                  class="archive-video-empty"
              >
                Select a recording to start playback.
              </div>

            </div>

          </template>

        </main>

      </div>

      <div
          v-if="analyticsResultsOpen"
          class="results-backdrop"
          @click.self="closeAnalyticsResults"
      >
        <section class="results-modal" role="dialog" aria-modal="true" aria-label="Analysis results">
          <header class="results-header">
            <div>
              <h3>Analysis results</h3>
              <span v-if="!analyticsEventsLoading">Found events: {{ analyticsEventsTotal }}</span>
            </div>
            <button type="button" class="close-button" aria-label="Close results" @click="closeAnalyticsResults">Ã—</button>
          </header>

          <div v-if="analyticsEventsLoading" class="loading">Loading results...</div>
          <div v-else-if="analyticsEventsError" class="error-message">{{ analyticsEventsError }}</div>
          <div v-else-if="analyticsEvents.length === 0" class="empty">No events were detected.</div>
          <div v-else class="results-list">
            <article v-for="event in analyticsEvents" :key="event.eventId" class="result-card">
              <div class="result-snapshot">
                <img
                    v-if="snapshotAvailable(event)"
                    :src="getSnapshotUrl(event)"
                    :alt="`Snapshot ${event.eventId}`"
                    @error="handleSnapshotError(event)"
                >
                <span v-else>No snapshot</span>
              </div>
              <div class="result-details">
                <strong>{{ formatDuration(event.videoTimeSeconds == null ? null : Math.round(event.videoTimeSeconds)) }}</strong>
                <span>{{ event.eventType }}</span>
                <span>{{ event.objectType || "Unknown object" }}</span>
                <span>Confidence: {{ formatConfidence(event.confidence) }}</span>
                <span>Direction: {{ eventDirection(event) }}</span>
              </div>
            </article>
          </div>
        </section>
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

.analytics-controls {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 16px;
}

.analytics-button {
  padding: 9px 14px;
  border: 0;
  border-radius: 6px;
  background: #3978ff;
  color: #fff;
  font: inherit;
  font-weight: 600;
  cursor: pointer;
}

.analytics-button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.analytics-stop-button {
  padding: 8px 12px;
  border: 1px solid #b42318;
  border-radius: 6px;
  background: #fff;
  color: #b42318;
  cursor: pointer;
}

.analytics-results-button {
  padding: 8px 12px;
  border: 1px solid #18864b;
  border-radius: 6px;
  background: #fff;
  color: #18864b;
  font: inherit;
  font-weight: 600;
  cursor: pointer;
}

.results-backdrop {
  position: fixed;
  inset: 0;
  z-index: 1100;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgba(0, 0, 0, 0.65);
}

.results-modal {
  width: min(820px, 100%);
  max-height: 85vh;
  overflow: auto;
  border-radius: 12px;
  background: #fff;
}

.results-header {
  position: sticky;
  top: 0;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid #ddd;
  background: #fff;
}

.results-header h3 {
  margin: 0 0 4px;
}

.results-header span {
  color: #667085;
  font-size: 14px;
}

.results-list {
  display: grid;
  gap: 12px;
  padding: 16px 20px 20px;
}

.result-card {
  display: grid;
  grid-template-columns: 180px 1fr;
  gap: 16px;
  overflow: hidden;
  border: 1px solid #e2e5e9;
  border-radius: 8px;
}

.result-snapshot {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 110px;
  background: #111;
  color: #aaa;
}

.result-snapshot img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.result-details {
  display: grid;
  align-content: center;
  gap: 4px;
  padding: 12px 12px 12px 0;
}

.analytics-progress {
  position: relative;
  width: 180px;
  height: 20px;
  overflow: hidden;
  border-radius: 10px;
  background: #e8edf5;
  text-align: center;
  font-size: 12px;
  line-height: 20px;
}

.analytics-progress-section {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.analytics-progress-details {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  color: #667085;
  font-size: 12px;
}

.analytics-progress-fill {
  position: absolute;
  inset: 0 auto 0 0;
  background: #7da5ff;
  transition: width 0.3s ease;
}

.analytics-progress-fill.indeterminate {
  width: 35%;
  animation: analytics-progress 1.2s linear infinite;
}

.analytics-progress span {
  position: relative;
  z-index: 1;
}

@keyframes analytics-progress {
  from { transform: translateX(-100%); }
  to { transform: translateX(300%); }
}

.analytics-status {
  color: #555;
  font-size: 14px;
}

.analytics-completed {
  color: #18864b;
}

.analytics-failed,
.analytics-rejected,
.analytics-error {
  color: #b42318;
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
  max-height: none;
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

  .result-card {
    grid-template-columns: 1fr;
  }

  .result-details {
    padding: 12px;
  }
}
</style>
