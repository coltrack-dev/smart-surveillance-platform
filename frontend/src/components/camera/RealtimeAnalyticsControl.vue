<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import {
  findLatestRealtimeAnalyticsJob,
  startRealtimeAnalytics,
  stopRealtimeAnalytics
} from "@/api/analyticsApi";
import type { AnalyticsJob } from "@/types/AnalyticsControl";

const props = defineProps<{
  cameraId: string;
}>();

const sourceUrl = ref(
    `rtsp://${window.location.hostname}:8554/${props.cameraId}`
);
const job = ref<AnalyticsJob | null>(null);
const loading = ref(false);
const errorMessage = ref<string | null>(null);
let pollTimer: number | undefined;

const active = computed(() =>
    ["REQUESTED", "RUNNING", "RETRYING", "STOP_REQUESTED"]
        .includes(job.value?.status ?? "")
);

async function refresh(): Promise<void> {
  job.value = await findLatestRealtimeAnalyticsJob(props.cameraId);
  if (job.value?.sourceUrl) {
    sourceUrl.value = job.value.sourceUrl;
  }
}

async function start(): Promise<void> {
  loading.value = true;
  errorMessage.value = null;
  try {
    job.value = await startRealtimeAnalytics(props.cameraId, {
      sourceUrl: sourceUrl.value,
      transport: "tcp",
      classes: [0],
      confidence: 0.5,
      devicePreference: "cuda:0",
      linePosition: 0.5,
      targetFps: 10
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
        {{ job?.status ?? "NOT STARTED" }}
      </span>
    </div>

    <input
        v-model.trim="sourceUrl"
        aria-label="Analytics RTSP URL"
        placeholder="rtsp://host:8554/path"
        :disabled="active || loading"
    >

    <div class="analytics-buttons">
      <button
          type="button"
          class="analytics-start"
          :disabled="active || loading || !sourceUrl"
          @click="start"
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

    <small v-if="job?.workerId">Worker: {{ job.workerId }}</small>
    <small v-if="errorMessage" class="analytics-error">{{ errorMessage }}</small>
  </section>
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

.analytics-error {
  color: #dc2626;
}
</style>
