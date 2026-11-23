<script setup lang="ts">
import { useRoute } from 'vue-router'
const route = useRoute()
</script>


<template>

  <div class="app-layout">

    <header v-if="!route.path.startsWith('/player/')" class="header">

      <h2>
        Smart Surveillance Platform
      </h2>

    </header>


    <main class="content" :class="{ 'player-content': route.path.startsWith('/player/') }">

      <router-view />

    </main>

  </div>

</template>
