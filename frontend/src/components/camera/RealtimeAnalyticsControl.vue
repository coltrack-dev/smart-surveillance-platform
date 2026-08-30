<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import {
  findLatestRealtimeAnalyticsJob,
  startRealtimeAnalytics,
  stopRealtimeAnalytics
} from "@/api/analyticsApi";
import type { AnalyticsJob } from "@/types/AnalyticsControl";
import type { AnalyticsProfileSettings } from "@/types/AnalyticsControl";
import AnalyticsProfileDialog from "@/components/analytics/AnalyticsProfileDialog.vue";
import { loadDefaultAnalyticsProfile, saveDefaultAnalyticsProfile } from "@/services/analyticsProfileSettings";

const props = defineProps<{
  cameraId: string;
  streamActive: boolean;
}>();

const sourceUrl = ref(
    `rtsp://${window.location.hostname}:8554/${props.cameraId}`
);
const job = ref<AnalyticsJob | null>(null);
const loading = ref(false);
const errorMessage = ref<string | null>(null);
const profileDialogOpen = ref(false);
const profile = ref(loadDefaultAnalyticsProfile());
const lineOrientation = ref<"HORIZONTAL" | "VERTICAL">("HORIZONTAL");
const linePosition = ref(0.5);
let pollTimer: number | undefined;

const active = computed(() =>
    ["REQUESTED", "RUNNING", "RETRYING", "STOP_REQUESTED"]
        .includes(job.value?.status ?? "")
);

const statusLabel = computed(() => {
  switch (job.value?.status) {
    case "REQUESTED": return "Waiting for worker";
    case "RUNNING": return "Analysis running";
    case "RETRYING": return "Reconnecting to video";
    case "STOP_REQUESTED": return "Stopping analysis";
    case "STOPPED": return "Analysis stopped";
    case "COMPLETED": return "Completed";
    case "FAILED": return "Analysis failed";
    case "REJECTED": return "Start rejected";
    default: return "Not started";
  }
});

const stageDescription = computed(() => {
  if (!props.streamActive && !active.value) return "Start the stream to enable realtime analysis.";
  switch (job.value?.status) {
    case "REQUESTED": return "The job was accepted and is waiting for an inference worker.";
    case "RUNNING": return "The worker is receiving frames and tracking objects.";
    case "RETRYING": return "Frames are unavailable; the worker is restoring the connection.";
    case "STOP_REQUESTED": return "The worker is finishing the current operation.";
    case "FAILED":
    case "REJECTED": return String(job.value?.details?.error ?? "Open service logs for details.");
    default: return "Configure the crossing line and start analysis.";
  }
});

async function refresh(): Promise<void> {
  job.value = await findLatestRealtimeAnalyticsJob(props.cameraId);
  if (job.value?.sourceUrl) {
    sourceUrl.value = job.value.sourceUrl;
  }
}

async function start(selected: AnalyticsProfileSettings, saveAsDefault: boolean): Promise<void> {
  profileDialogOpen.value = false;
  profile.value = selected;
  if (saveAsDefault) saveDefaultAnalyticsProfile(selected);
  loading.value = true;
  errorMessage.value = null;
  try {
    const vertical = lineOrientation.value === "VERTICAL";
    job.value = await startRealtimeAnalytics(props.cameraId, {
      sourceUrl: sourceUrl.value,
      transport: "tcp",
      model: selected.model,
      classes: selected.classes,
      confidence: selected.confidence,
      devicePreference: selected.devicePreference,
      linePosition: 0.5,
      lines: [{
        id: "main-line",
        start: vertical
            ? { x: linePosition.value, y: 0.05 }
            : { x: 0.05, y: linePosition.value },
        end: vertical
            ? { x: linePosition.value, y: 0.95 }
            : { x: 0.95, y: linePosition.value },
        anchor: "BOTTOM_CENTER",
        allowedDirections: [],
        directionLabels: vertical
            ? { A_TO_B: "RIGHT_TO_LEFT", B_TO_A: "LEFT_TO_RIGHT" }
            : { A_TO_B: "DOWN", B_TO_A: "UP" },
        allowedClasses: [0],
        cooldownSeconds: 5,
        hysteresis: 0.02,
        minimumTrackAgeFrames: 3
      }],
      targetFps: selected.targetFps
    });
  } catch (error) {
    console.error("Unable to start realtime analytics", error);
    errorMessage.value = "Unable to start analytics";
  } finally {
    loading.value = false;
  }
}

async function stop(): Promise<void> {
  loading.value = true;
  errorMessage.value = null;
  try {
    job.value = await stopRealtimeAnalytics(props.cameraId);
  } catch (error) {
    console.error("Unable to stop realtime analytics", error);
    errorMessage.value = "Unable to stop analytics";
  } finally {
    loading.value = false;
  }
}

onMounted(async () => {
  try {
    await refresh();
  } catch (error) {
    console.error("Unable to load realtime analytics status", error);
  }
  pollTimer = window.setInterval(() => {
    void refresh().catch(error => {
      console.error("Unable to refresh realtime analytics status", error);
    });
  }, 3000);
});

onBeforeUnmount(() => {
  if (pollTimer !== undefined) {
    window.clearInterval(pollTimer);
  }
});
</script>

<template>
  <section class="analytics-control">
    <div class="analytics-header">
      <strong>Realtime analytics</strong>
      <span class="analytics-status" :class="job?.status.toLowerCase()">
        {{ statusLabel }}
      </span>
    </div>

    <input
        v-model.trim="sourceUrl"
        aria-label="Analytics RTSP URL"
        placeholder="rtsp://host:8554/path"
        :disabled="active || loading"
    >

    <div class="line-settings">
      <label>
        Crossing line
        <select v-model="lineOrientation" :disabled="active || loading">
          <option value="HORIZONTAL">Horizontal</option>
          <option value="VERTICAL">Vertical</option>
        </select>
      </label>
      <label>
        Position
        <input
            v-model.number="linePosition"
            type="number"
            min="0.05"
            max="0.95"
            step="0.05"
            :disabled="active || loading"
        >
      </label>
    </div>

    <div class="analytics-buttons">
      <button
          type="button"
          class="analytics-start"
          :disabled="active || loading || !sourceUrl || !streamActive"
          @click="profileDialogOpen = true"
      >
        Start analytics
      </button>
      <button
          type="button"
          :disabled="!active || loading"
          @click="stop"
      >
        Stop analytics
      </button>
    </div>

    <small class="analytics-stage">{{ stageDescription }}</small>

    <small v-if="job?.workerId">Worker: {{ job.workerId }}</small>
    <small v-if="errorMessage" class="analytics-error">{{ errorMessage }}</small>
  </section>
  <AnalyticsProfileDialog
      :open="profileDialogOpen"
      :initial-profile="profile"
      @close="profileDialogOpen = false"
      @run="start"
  />
</template>

<style scoped>
.analytics-control {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid #e5e7eb;
}

.analytics-header,
.analytics-buttons {
  display: flex;
  align-items: center;
  gap: 10px;
}

.analytics-header {
  justify-content: space-between;
  margin-bottom: 8px;
}

.analytics-status {
  color: #64748b;
  font-size: 11px;
  font-weight: 700;
}

.analytics-status.running {
  color: #16a34a;
}

.analytics-status.retrying,
.analytics-status.requested,
.analytics-status.stop_requested {
  color: #d97706;
}

.analytics-status.failed,
.analytics-status.rejected {
  color: #dc2626;
}

input {
  box-sizing: border-box;
  width: 100%;
  padding: 8px;
  border: 1px solid #cbd5e1;
  border-radius: 6px;
}

.analytics-buttons {
  margin-top: 8px;
}

.analytics-start {
  color: white;
  background: #7c3aed;
}

small {
  display: block;
  margin-top: 6px;
  color: #64748b;
}

.analytics-stage {
  line-height: 1.4;
}

.analytics-error {
  color: #dc2626;
}
</style>
