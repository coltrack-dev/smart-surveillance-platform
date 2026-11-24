<script setup lang="ts">
import Hls, { type HlsConfig, type Loader, type LoaderCallbacks, type LoaderConfiguration, type LoaderContext, type LoaderResponse, type LoaderStats } from 'hls.js'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { API_URL } from '@/config'
import { getAccessToken } from '@/auth/oidc'
import { getSelectedTenant } from '@/api/http'

const route = useRoute()
const video = ref<HTMLVideoElement | null>(null)
const loading = ref(true)
const offline = ref(false)
const message = ref('Connecting to the live stream…')
const copied = ref(false)
let hls: Hls | null = null
let retryCount = 0
let cachedAuthorization: { token: string; expiresAt: number; playlistUrl: string } | null = null
let nativeRenewalTimer: number | undefined

const publicId = computed(() => String(route.params.publicId ?? ''))
const playerUrl = computed(() => window.location.href)
const embedCode = computed(() => {
  const url = new URL(playerUrl.value)
  url.searchParams.set('embed', '1')
  return `<iframe src="${url}" title="Live broadcast" allow="autoplay; fullscreen; picture-in-picture" allowfullscreen loading="lazy"></iframe>`
})

async function playbackAuthorization() {
  if (cachedAuthorization && cachedAuthorization.expiresAt > Date.now() + 60_000) return cachedAuthorization
  const apiRoot = API_URL.replace(/\/$/, '')
  const tokenUrl = `${apiRoot}/playback/${encodeURIComponent(publicId.value)}/token`
  let response = await fetch(tokenUrl, { cache: 'no-store' })
  // PUBLIC playback stays anonymous. Retry a 401 with a user token only when
  // this browser already has an authenticated Streaming session and tenant.
  if (response.status === 401) {
    const [accessToken, storedTenantId] = await Promise.all([getAccessToken(), Promise.resolve(getSelectedTenant())])
    const tenantId = storedTenantId || (typeof route.query.tenant === 'string' ? route.query.tenant : null)
    if (accessToken && tenantId) response = await fetch(tokenUrl, {
      cache: 'no-store',
      headers: { Authorization: `Bearer ${accessToken}`, 'X-Tenant-ID': tenantId }
    })
  }
  if (!response.ok) throw new Error(response.status === 401 ? 'This broadcast is private.' : 'The broadcast is offline or unavailable.')
  const result = await response.json() as { token: string; expiresAt: string; playlistUrl: string }
  // The API returns a path-only URL. Resolve it against the gateway origin,
  // never the player origin, which would serve the SPA fallback instead of HLS.
  cachedAuthorization = {
    ...result,
    playlistUrl: new URL(result.playlistUrl, `${apiRoot}/`).toString(),
    expiresAt: Date.parse(result.expiresAt),
  }
  return cachedAuthorization
}

class RenewingHlsLoader implements Loader<LoaderContext> {
  private readonly inner: Loader<LoaderContext>
  constructor(config: HlsConfig) {
    this.inner = new Hls.DefaultConfig.loader(config) as Loader<LoaderContext>
  }
  get context() { return this.inner.context }
  get stats() { return this.inner.stats }
  destroy() { this.inner.destroy() }
  abort() { this.inner.abort() }
  load(context: LoaderContext, config: LoaderConfiguration, callbacks: LoaderCallbacks<LoaderContext>) {
    if (!context.url.includes('/broadcast-play/')) {
      this.inner.load(context, config, callbacks)
      return
    }
    playbackAuthorization().then(({ token }) => {
      const securedUrl = new URL(context.url, window.location.href)
      securedUrl.searchParams.set('token', token)
      this.inner.load({ ...context, url: securedUrl.toString() }, config, {
        onSuccess: (response: LoaderResponse, stats: LoaderStats, _context: LoaderContext, details: any) => callbacks.onSuccess(response, stats, context, details),
        onError: (error, _context, details, stats) => callbacks.onError(error, context, details, stats),
        onTimeout: (stats, _context, details) => callbacks.onTimeout(stats, context, details),
        onAbort: (stats, _context, details) => callbacks.onAbort?.(stats, context, details),
        onProgress: (stats, _context, data, details) => callbacks.onProgress?.(stats, context, data, details)
      })
    }).catch(error => {
      loading.value = false
      offline.value = true
      message.value = error instanceof Error ? error.message : 'Playback authorization failed.'
      callbacks.onError({ code: 401, text: message.value }, context, null, this.inner.stats)
    })
  }
}

async function attachPlayer() {
  if (!video.value || !publicId.value) {
    loading.value = false
    offline.value = true
    message.value = 'This watch link is invalid.'
    return
  }
  offline.value = false
  loading.value = true
  message.value = retryCount ? 'Reconnecting to the live stream…' : 'Connecting to the live stream…'
  if (Hls.isSupported()) {
    hls?.destroy()
    hls = new Hls({ enableWorker: true, lowLatencyMode: false, maxBufferLength: 20, loader: RenewingHlsLoader })
    try {
      const authorization = await playbackAuthorization()
      hls.loadSource(authorization.playlistUrl)
    } catch (error) {
      loading.value = false
      offline.value = true
      message.value = error instanceof Error ? error.message : 'Broadcast authorization failed.'
      return
    }
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
    try {
      const authorization = await playbackAuthorization()
      video.value.src = authorization.playlistUrl
      window.clearTimeout(nativeRenewalTimer)
      nativeRenewalTimer = window.setTimeout(() => { void attachPlayer() }, Math.max(1000, authorization.expiresAt - Date.now() - 60_000))
    } catch (error) {
      loading.value = false
      offline.value = true
      message.value = error instanceof Error ? error.message : 'Broadcast authorization failed.'
      return
    }
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
onBeforeUnmount(() => { hls?.destroy(); window.clearTimeout(nativeRenewalTimer) })
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
        <div><strong>Live broadcast</strong><span>This permanent watch link issues short-lived playback authorization.</span></div>
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
