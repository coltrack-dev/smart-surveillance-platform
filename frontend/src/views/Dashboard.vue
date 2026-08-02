<script setup lang="ts">

import {
  onMounted,
  ref
} from 'vue';

import {
  getCameras,
  startStream,
  stopStream,
  type Camera
} from '../api/camera.ts';


import CameraPlayer from '../components/camera/CameraPlayer.vue';


const cameras =
    ref<Camera[]>([]);


const streams =
    ref<Record<string,string>>({});


async function load(){

  cameras.value =
      await getCameras();

}


async function start(camera:Camera){

  await startStream(camera.id);


  streams.value[camera.id] =
      `http://localhost:8094/hls/${camera.id}/index.m3u8`;

}


async function stop(camera:Camera){

  await stopStream(camera.id);


  delete streams.value[camera.id];

}


onMounted(load);


</script>


<template>

  <div class="page">

    <h1>
      Surveillance Dashboard
    </h1>


    <div class="grid">


      <div
          v-for="camera in cameras"
          :key="camera.id"
          class="camera-card"
      >


        <div class="camera-header">

          <h3>
            {{ camera.name }}
          </h3>


          <span
              class="status"
              :class="camera.status.toLowerCase()"
          >
{{camera.status}}
</span>

        </div>


        <button
            @click="start(camera)"
        >
          Start
        </button>


        <button
            class="stop"
            @click="stop(camera)"
        >
          Stop
        </button>


        <div
            class="player"
            v-if="streams[camera.id]"
        >

          <CameraPlayer
              :url="streams[camera.id]"
          />

        </div>


      </div>


    </div>

  </div>

</template>

<style scoped>

.grid {

  display:grid;
  grid-template-columns:repeat(2,1fr);
  gap:20px;

}


.camera-card {

  border:1px solid #ccc;
  padding:15px;
  border-radius:8px;

}

</style>
