<script setup lang="ts">
import Hls from 'hls.js'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { API_URL } from '@/config'

const route = useRoute()
const video = ref<HTMLVideoElement | null>(null)
const loading = ref(true)
const offline = ref(false)
const message = ref('Connecting to the live stream…')
const copied = ref(false)
let hls: Hls | null = null
let retryCount = 0

const broadcastId = computed(() => String(route.params.broadcastId ?? ''))
const token = computed(() => String(route.query.token ?? ''))
const playerUrl = computed(() => window.location.href)
const embedCode = computed(() => {
  const url = new URL(playerUrl.value)
  url.searchParams.set('embed', '1')
  return `<iframe src="${url}" title="Live broadcast" allow="autoplay; fullscreen; picture-in-picture" allowfullscreen loading="lazy"></iframe>`
})

function playlistUrl() {
  const origin = new URL(API_URL, window.location.href).origin
  return `${origin}/broadcast-play/${encodeURIComponent(broadcastId.value)}/index.m3u8?token=${encodeURIComponent(token.value)}`
}

function attachPlayer() {
  if (!video.value || !broadcastId.value || !token.value) {
    loading.value = false
    offline.value = true
    message.value = 'This playback link is missing or invalid.'
    return
  }
  offline.value = false
  loading.value = true
  message.value = retryCount ? 'Reconnecting to the live stream…' : 'Connecting to the live stream…'
  if (Hls.isSupported()) {
    hls?.destroy()
    hls = new Hls({ enableWorker: true, lowLatencyMode: false, maxBufferLength: 20 })
    hls.loadSource(playlistUrl())
    hls.attachMedia(video.value)
    hls.on(Hls.Events.MANIFEST_PARSED, () => {
      loading.value = false
      message.value = ''
      video.value?.play().catch(() => undefined)
    })
    hls.on(Hls.Events.ERROR, (_event, data) => {
      if (!data.fatal) return
      loading.value = false
      if (data.type === Hls.ErrorTypes.NETWORK_ERROR && retryCount < 3) {
        retryCount++
        window.setTimeout(() => hls?.startLoad(), 1200 * retryCount)
      } else if (data.type === Hls.ErrorTypes.MEDIA_ERROR && retryCount < 3) {
        retryCount++
        hls?.recoverMediaError()
      } else {
        offline.value = true
        message.value = 'The broadcast is offline or this link has expired.'
      }
    })
    return
  }
  if (video.value.canPlayType('application/vnd.apple.mpegurl')) {
    video.value.src = playlistUrl()
    video.value.addEventListener('loadedmetadata', () => {
      loading.value = false
      message.value = ''
      video.value?.play().catch(() => undefined)
    }, { once: true })
    video.value.addEventListener('error', () => {
      loading.value = false
      offline.value = true
      message.value = 'The broadcast is offline or this link has expired.'
    }, { once: true })
    return
  }
  loading.value = false
  offline.value = true
  message.value = 'This browser does not support HLS playback.'
}

async function copyLink() {
  await navigator.clipboard.writeText(playerUrl.value)
  copied.value = true
  window.setTimeout(() => copied.value = false, 1800)
}

async function copyEmbed() {
  await navigator.clipboard.writeText(embedCode.value)
  copied.value = true
  window.setTimeout(() => copied.value = false, 1800)
}

onMounted(attachPlayer)
onBeforeUnmount(() => hls?.destroy())
</script>

<template>
  <main class="player-page" :class="{ embedded: route.query.embed === '1' }">
    <section class="player-shell">
      <div class="player-frame">
        <video ref="video" controls playsinline autoplay aria-label="Live broadcast player" />
        <div v-if="loading || offline" class="player-overlay" role="status">
          <span class="status-dot" :class="{ offline }" />
          <p>{{ message }}</p>
          <button v-if="offline" type="button" @click="retryCount = 0; attachPlayer()">Try again</button>
        </div>
      </div>
      <footer v-if="route.query.embed !== '1'" class="player-footer">
        <div><strong>Live broadcast</strong><span>Share this link while it is valid.</span></div>
        <div class="player-actions">
          <button type="button" @click="copyLink">{{ copied ? 'Copied' : 'Copy link' }}</button>
          <button type="button" class="secondary" @click="copyEmbed">Copy embed code</button>
        </div>
      </footer>
    </section>
  </main>
</template>

<style scoped>
.player-page { min-height: 100vh; display:grid; place-items:center; padding:clamp(12px, 4vw, 48px); background:#0b111b; color:#f5f7fb; }
.player-shell { width:min(1100px, 100%); overflow:hidden; border:1px solid #273449; border-radius:16px; background:#111a28; box-shadow:0 24px 80px #0007; }
.player-frame { position:relative; aspect-ratio:16/9; background:#030609; }
video { display:block; width:100%; height:100%; object-fit:contain; }
.player-overlay { position:absolute; inset:0; display:flex; flex-direction:column; justify-content:center; align-items:center; gap:12px; padding:24px; background:#030609b8; text-align:center; }
.player-overlay p { margin:0; color:#d0d8e5; }
.status-dot { width:10px; height:10px; border-radius:50%; background:#35d39a; box-shadow:0 0 14px #35d39a; }
.status-dot.offline { background:#f47777; box-shadow:0 0 14px #f47777; }
.player-footer { display:flex; align-items:center; justify-content:space-between; gap:16px; padding:16px 20px; }
.player-footer div:first-child { display:grid; gap:4px; }.player-footer span { color:#9eabc0; font-size:13px; }
.player-actions { display:flex; gap:8px; }
button { border:0; border-radius:8px; padding:10px 14px; background:#4c8dff; color:white; cursor:pointer; font-weight:600; }
button.secondary { background:#26364e; }
.embedded { min-height:0; padding:0; }.embedded .player-shell { width:100%; border:0; border-radius:0; box-shadow:none; }.embedded .player-frame { width:100%; }
@media(max-width:620px) { .player-page { padding:0; align-items:start; }.player-shell { border-radius:0; }.player-footer { align-items:flex-start; flex-direction:column; }.player-actions { width:100%; }.player-actions button { flex:1; } }
</style>
