<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from "vue";
import Hls from "hls.js";

const props = defineProps<{
  url?: string;
}>();

// HTML5 video элемент
const video = ref<HTMLVideoElement>();

// Экземпляр hls.js
let hls: Hls | null = null;

/**
 * Загрузка HLS потока.
 */
async function loadPlayer() {

  if (!video.value || !props.url) {
    return;
  }

  // Уничтожаем старый поток перед загрузкой нового
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

    await video.value.play();

    return;
  }

  /*
   * Chrome/Firefox используют hls.js.
   */
  if (!Hls.isSupported()) {

    console.error(
        "HLS is not supported"
    );

    return;

  }

  hls = new Hls({
    enableWorker: true,
    lowLatencyMode: true
  });

  /*
   * Поток подключен к video элементу.
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
   * Manifest успешно загружен.
   */
  hls.on(
      Hls.Events.MANIFEST_PARSED,
      async () => {

        console.log(
            "HLS manifest parsed"
        );

        try {

          await video.value?.play();

        } catch (error) {

          console.warn(
              "Autoplay blocked",
              error
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

      }
  );

  hls.attachMedia(
      video.value
  );
}

/*
 * Перезапуск плеера при изменении URL.
 */
watch(
    () => props.url,
    () => loadPlayer()
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
    () => {

      if (hls) {

        hls.destroy();
        hls = null;

      }

    }
);
</script>

<template>
  <video
      ref="video"
      controls
      autoplay
      muted
      playsinline
      class="player"
  />
</template>

<style scoped>
.player {
  width: 100%;
  height: 240px;
  background: black;
  border-radius: 8px;
}
</style>
