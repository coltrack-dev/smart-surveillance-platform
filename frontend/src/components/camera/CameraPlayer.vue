<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from "vue";
import Hls from "hls.js";

const props = defineProps<{
  url?: string;
  connecting?: boolean;
  proportional?: boolean;
}>();

// HTML5 video элемент
const video = ref<HTMLVideoElement>();

// Состояние проигрывателя
const loading = ref(false);
const error = ref(false);
const playing = ref(false);
const autoplayBlocked = ref(false);

// Экземпляр hls.js
let hls: Hls | null = null;
let networkRetryTimer: number | null = null;
let networkRetryCount = 0;
const MAX_NETWORK_RETRIES = 10;
let mediaRetryCount = 0;
const MAX_MEDIA_RETRIES = 3;
let firstFrameTimer: number | null = null;
let loadGeneration = 0;

function clearTimers() {
  if (networkRetryTimer !== null) {
    window.clearTimeout(networkRetryTimer);
    networkRetryTimer = null;
  }
  if (firstFrameTimer !== null) {
    window.clearTimeout(firstFrameTimer);
    firstFrameTimer = null;
  }
}

function markPlaying() {
  clearTimers();
  loading.value = false;
  error.value = false;
  autoplayBlocked.value = false;
  playing.value = true;
}

function armFirstFrameTimeout(generation: number) {
  if (firstFrameTimer !== null) window.clearTimeout(firstFrameTimer);
  firstFrameTimer = window.setTimeout(() => {
    if (generation !== loadGeneration || playing.value || props.connecting) return;
    loading.value = false;
    error.value = true;
  }, 15000);
}

/**
 * Загрузка HLS потока.
 */
async function loadPlayer() {

  if (!video.value || !props.url) {
    return;
  }

  loading.value = true;
  error.value = false;
  playing.value = false;
  autoplayBlocked.value = false;
  networkRetryCount = 0;
  mediaRetryCount = 0;
  clearTimers();
  const generation = ++loadGeneration;
  armFirstFrameTimeout(generation);

  const onVideoStarted = () => {
    if (generation === loadGeneration) markPlaying();
  };
  video.value.onplaying = onVideoStarted;
  video.value.oncanplay = onVideoStarted;
  video.value.onloadeddata = onVideoStarted;
  video.value.onwaiting = () => {
    if (generation === loadGeneration && !props.connecting) loading.value = true;
  };

  // Уничтожаем предыдущий поток
  if (hls) {

    hls.destroy();
    hls = null;

  }

  /*
   * Safari имеет встроенную поддержку HLS.
   */
  if (
      video.value.canPlayType(
          "application/vnd.apple.mpegurl"
      )
  ) {

    video.value.src = props.url;

    try {

      await video.value.play();

    } catch (e) {

      console.warn(
          "Autoplay blocked",
          e
      );

      if (generation === loadGeneration) {
        loading.value = false;
        autoplayBlocked.value = true;
      }

    }

    return;
  }

  /*
   * Chrome / Firefox используют hls.js.
   */
  if (!Hls.isSupported()) {

    console.error(
        "HLS is not supported"
    );

    loading.value = false;
    error.value = true;

    return;

  }

  hls = new Hls({
    enableWorker: true,
    lowLatencyMode: true,
    manifestLoadingMaxRetry: 6,
    manifestLoadingRetryDelay: 1000,
    levelLoadingMaxRetry: 6,
    fragLoadingMaxRetry: 6
  });

  /*
   * Поток подключен к video.
   */
  hls.on(
      Hls.Events.MEDIA_ATTACHED,
      () => {

        console.log(
            "HLS media attached"
        );

        hls?.loadSource(
            props.url!
        );

      }
  );

  /*
   * Manifest загружен.
   */
  hls.on(
      Hls.Events.MANIFEST_PARSED,
      async () => {

        console.log(
            "HLS manifest parsed"
        );

        try {

          await video.value?.play();

        } catch (e) {

          console.warn(
              "Autoplay blocked",
              e
          );

          if (generation === loadGeneration) {
            loading.value = false;
            autoplayBlocked.value = true;
          }

        }

      }
  );

  /*
   * Ошибки HLS.
   */
  hls.on(
      Hls.Events.ERROR,
      (_, data) => {

        console.error(
            "HLS error",
            data
        );

        if (!data.fatal || generation !== loadGeneration) return;

        if (
            data.type === Hls.ErrorTypes.NETWORK_ERROR &&
            networkRetryCount < MAX_NETWORK_RETRIES
        ) {
          networkRetryCount += 1;
          loading.value = true;
          error.value = false;
          if (networkRetryTimer !== null) {
            window.clearTimeout(networkRetryTimer);
          }
          networkRetryTimer = window.setTimeout(() => {
            if (generation !== loadGeneration) return;
            hls?.loadSource(props.url!);
            hls?.startLoad();
            armFirstFrameTimeout(generation);
          }, 2000);
          return;
        }

        if (data.type === Hls.ErrorTypes.MEDIA_ERROR
            && mediaRetryCount < MAX_MEDIA_RETRIES) {
          mediaRetryCount += 1;
          hls?.recoverMediaError();
          return;
        }

        loading.value = false;
        error.value = true;

      }
  );

  /*
   * Видео действительно начало воспроизводиться.
   */
//  video.value.addEventListener(
//      "timeupdate",
//      () => {
//        console.log(
//            "VIDEO TIME",
//            video.value?.currentTime
//        );
//      }
//  );

  hls.attachMedia(
      video.value
  );

}

/*
 * Остановка проигрывателя.
 */
function stopPlayer() {

  loadGeneration += 1;

  loading.value = false;
  error.value = false;
  playing.value = false;
  autoplayBlocked.value = false;

  clearTimers();

  if (hls) {

    hls.destroy();
    hls = null;

  }

  if (video.value) {

    video.value.onplaying = null;
    video.value.oncanplay = null;
    video.value.onloadeddata = null;
    video.value.onwaiting = null;

    video.value.pause();
    video.value.removeAttribute(
        "src"
    );
    video.value.load();

  }

}

/*
 * Перезапуск при изменении URL.
 */
watch(
    () => props.url,
    (newUrl) => {

      if (!newUrl) {

        stopPlayer();

        return;

      }

      loadPlayer();

    }
);

/* A backend reconnect normally keeps the same HLS URL. Reload explicitly
 * when RECONNECTING/STARTING changes back to RUNNING. */
watch(
    () => props.connecting,
    (connecting, wasConnecting) => {
      if (!connecting && wasConnecting && props.url) void loadPlayer();
    }
);

function retryPlayer() {
  void loadPlayer();
}

async function resumePlayback() {
  try {
    await video.value?.play();
  } catch (playError) {
    console.warn("Unable to resume video", playError);
  }
}

/*
 * Первичная загрузка.
 */
onMounted(
    loadPlayer
);

/*
 * Очистка ресурсов.
 */
onUnmounted(
    stopPlayer
);

async function seekTo(seconds: number, autoplay = false): Promise<void> {
  if (!video.value || !Number.isFinite(seconds)) return;
  video.value.currentTime = Math.max(0, seconds);
  if (autoplay) await video.value.play();
}

defineExpose({ seekTo });
</script>

<template>

  <div
      class="player-container"
      :class="{ proportional: props.proportional }"
  >

    <video
        ref="video"
        controls
        autoplay
        muted
        playsinline
        class="player"
        :class="{ proportional: props.proportional }"
    />

    <div v-if="loading && !connecting" class="overlay">
      <div class="spinner"></div>
      <div class="message">
        Connecting video stream...
      </div>
    </div>

    <div
        v-if="connecting"
        class="overlay"
    >
      <div class="spinner"></div>

      <div class="message">
        Connecting video stream...
      </div>
    </div>

    <div
        v-if="!connecting && !loading && !playing && !error && !autoplayBlocked"
        class="overlay"
    >
      <div class="message">
        No video stream
      </div>
    </div>

    <div v-if="autoplayBlocked && !playing" class="overlay">
      <button type="button" class="retry-button" @click="resumePlayback">
        Play video
      </button>
    </div>

    <div
        v-if="error && !connecting"
        class="overlay error"
    >

      <div class="message">
        Stream unavailable
      </div>

      <button type="button" class="retry-button" @click="retryPlayer">
        Retry
      </button>

    </div>

  </div>

</template>

<style scoped>

.player-container {

  position: relative;
  width: 100%;
  height: 240px;
  border-radius: 8px;
  overflow: hidden;
  background: black;

}

.player {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.player-container.proportional {
  height: auto;
  aspect-ratio: auto;
}

.player.proportional {
  display: block;
  width: 100%;
  height: auto;
  aspect-ratio: auto;
  object-fit: contain;
}

.overlay {

  position: absolute;
  inset: 0;

  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;

  gap: 14px;

  background: #111;
  color: white;

}

.error {

  background: #3b1111;

}

.message {

  font-size: 15px;
  color: #ddd;

}

.spinner {

  width: 40px;
  height: 40px;

  border: 4px solid rgba(255,255,255,.2);
  border-top-color: #4f8cff;
  border-radius: 50%;

  animation: spin 1s linear infinite;

}

.retry-button {
  padding: 8px 14px;
  border: 1px solid #7aa7ff;
  border-radius: 6px;
  background: #1d4ed8;
  color: white;
  cursor: pointer;
}

@keyframes spin {

  to {

    transform: rotate(360deg);

  }

}

</style>
