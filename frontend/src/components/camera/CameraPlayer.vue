<script setup lang="ts">

import { onMounted, onBeforeUnmount, ref } from 'vue';
import Hls from 'hls.js';


const props = defineProps<{
  url:string
}>();


const video =
    ref<HTMLVideoElement | null>(null);


let hls:Hls|null = null;


onMounted(()=>{

  if (!video.value)
    return;


  if (Hls.isSupported()) {

    hls = new Hls();

    hls.loadSource(props.url);

    hls.attachMedia(video.value);

  }
  else {

    video.value.src = props.url;

  }

});


onBeforeUnmount(()=>{

  hls?.destroy();

});

</script>


<template>

  <video
      ref="video"
      controls
      autoplay
      muted
      style="width:100%;"
  />

</template>
