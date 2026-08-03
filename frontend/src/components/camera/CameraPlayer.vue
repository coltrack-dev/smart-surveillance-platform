<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from "vue";
import Hls from "hls.js";

const props = defineProps<{
  url?: string;
}>();

// HTML5 video элемент
const video = ref<HTMLVideoElement>();

// Состояние проигрывателя
const loading = ref(false);
const error = ref(false);
const playing = ref(false);

// Экземпляр hls.js
let hls: Hls | null = null;

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
    lowLatencyMode: true
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

        if (data.fatal) {

          loading.value = false;
          error.value = true;

        }

      }
  );

  /*
   * Видео действительно начало воспроизводиться.
   */
  video.value.addEventListener(
      "playing",
      () => {

        loading.value = false;
        error.value = false;
        playing.value = true;

      },
      {
        once: true
      }
  );

  hls.attachMedia(
      video.value
  );

}

/*
 * Остановка проигрывателя.
 */
function stopPlayer() {

  loading.value = false;
  error.value = false;
  playing.value = false;

  if (hls) {

    hls.destroy();
    hls = null;

  }

  if (video.value) {

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
</script>

<template>

  <div class="player-container">

    <video
        v-show="playing"
        ref="video"
        controls
        autoplay
        muted
        playsinline
        class="player"
    />

    <div
        v-if="loading"
        class="overlay"
    >

      <div class="spinner"></div>

      <div class="message">
        Connecting video stream...
      </div>

    </div>

    <div
        v-if="!loading && !playing && !error"
        class="overlay"
    >

      <div class="message">
        No video stream
      </div>

    </div>

    <div
        v-if="error"
        class="overlay error"
    >

      <div class="message">
        Stream unavailable
      </div>

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

@keyframes spin {

  to {

    transform: rotate(360deg);

  }

}

</style>
