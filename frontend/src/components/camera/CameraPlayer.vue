<script setup lang="ts">
import {onMounted, onUnmounted, ref, watch} from "vue";

const props = defineProps<{

  url?: string

}>();

const video = ref<HTMLVideoElement>();

let hls: any = null;

async function loadPlayer() {

  if (!video.value)
    return;

  if (!props.url)
    return;

  if (hls) {

    hls.destroy();

    hls = null;

  }

  /*
   * Safari
   */

  if (video.value.canPlayType("application/vnd.apple.mpegurl")) {

    video.value.src = props.url;

    return;

  }

  /*
   * hls.js
   */

  const Hls =
      (await import("hls.js")).default;

  if (!Hls.isSupported())
    return;

  hls = new Hls();

  hls.loadSource(props.url);

  hls.attachMedia(video.value);

}

watch(
    () => props.url,
    loadPlayer
);

onMounted(loadPlayer);

onUnmounted(() => {

  if (hls)
    hls.destroy();

});
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
