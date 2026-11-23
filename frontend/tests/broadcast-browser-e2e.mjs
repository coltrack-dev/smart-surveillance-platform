import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { chromium } from 'playwright'

const gateway = (process.env.BROADCAST_TEST_GATEWAY ?? 'http://127.0.0.1:18081').replace(/\/$/, '')
const player = (process.env.BROADCAST_TEST_PLAYER ?? 'http://localhost:5173').replace(/\/$/, '')
const mediaContainers = (process.env.BROADCAST_IT_LOG_CONTAINERS ?? 'broadcast-it-camera-gateway-1,broadcast-it-stream-service-1,broadcast-it-mediamtx-1,broadcast-it-video-ingest-agent-1,broadcast-it-broadcast-player-1').split(',')
const managementToken = process.env.BROADCAST_MANAGEMENT_TOKEN
assert.ok(managementToken, 'BROADCAST_MANAGEMENT_TOKEN is required')

async function api(path, { method = 'GET', body, expected = 200 } = {}) {
  const response = await fetch(`${gateway}${path}`, {
    method,
    headers: {
      ...(body ? { 'content-type': 'application/json' } : {}),
      authorization: `Bearer ${managementToken}`,
    },
    body: body ? JSON.stringify(body) : undefined,
  })
  assert.equal(response.status, expected, `${method} ${path} returned ${response.status}`)
  return response.status === 204 ? null : response.json()
}

async function waitForDecodedVideo(video, timeoutMs = 45_000) {
  await video.waitFor({ state: 'visible', timeout: timeoutMs })
  await video.evaluate(element => { element.muted = true; void element.play() })
  const deadline = Date.now() + timeoutMs
  let decoded
  while (Date.now() < deadline) {
    decoded = await video.evaluate(v => ({
      ready: v.readyState >= 2,
      width: v.videoWidth,
      height: v.videoHeight,
      frames: v.getVideoPlaybackQuality().totalVideoFrames,
      currentTime: v.currentTime,
    }))
    if (decoded.ready && decoded.width > 0 && decoded.height > 0 && decoded.frames >= 10) return decoded
    await new Promise(resolve => setTimeout(resolve, 250))
  }
  throw new Error(`video did not decode frames; last metrics=${JSON.stringify(decoded)}`)
}

async function waitForPlaylist(publicId, timeoutMs = 45_000) {
  const deadline = Date.now() + timeoutMs
  let lastStatus = 0
  while (Date.now() < deadline) {
    const auth = await fetch(`${gateway}/api/playback/${publicId}/token`, { cache: 'no-store' })
    if (auth.ok) {
      const authorization = await auth.json()
      const playlistUrl = new URL(authorization.playlistUrl, gateway)
      const playlist = await fetch(playlistUrl, { cache: 'no-store', referrerPolicy: 'no-referrer' })
      lastStatus = playlist.status
      if (playlist.ok && (await playlist.text()).startsWith('#EXTM3U')) return Date.now()
    } else lastStatus = auth.status
    await new Promise(resolve => setTimeout(resolve, 1000))
  }
  throw new Error(`protected HLS playlist did not become ready; last HTTP status=${lastStatus}`)
}

function ffmpegPids() {
  const container = process.env.BROADCAST_IT_AGENT_CONTAINER ?? 'broadcast-it-video-ingest-agent-1'
  const listing = execFileSync('docker', ['top', container, '-eo', 'pid,args'], { encoding: 'utf8' })
  return listing.split(/\r?\n/).slice(1)
    .filter(line => /(?:^|\s)ffmpeg(?:\s|$)/i.test(line))
    .map(line => line.trim().split(/\s+/, 1)[0])
    .sort()
}

function logsContain(secret) {
  if (!secret) return false
  return mediaContainers.some(container => {
    const logs = execFileSync('docker', ['logs', '--since', '10m', container], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })
    return logs.includes(secret)
  })
}

function containerStats() {
  const containers = [
    'broadcast-it-stream-service-1', 'broadcast-it-video-ingest-agent-1',
    'broadcast-it-mediamtx-1', 'broadcast-it-broadcast-player-1',
  ]
  const output = execFileSync('docker', ['stats', '--no-stream', '--format', '{{json .}}', ...containers], { encoding: 'utf8' })
  return output.trim().split(/\r?\n/).filter(Boolean).map(line => {
    const value = JSON.parse(line)
    return { name: value.Name, cpu: value.CPUPerc, memory: value.MemUsage }
  })
}

async function concurrencyProbe(publicId, count) {
  const authResponse = await fetch(`${gateway}/api/playback/${publicId}/token`, { cache: 'no-store' })
  assert.equal(authResponse.status, 200, 'token must be available for concurrency probe')
  const auth = await authResponse.json()
  const playlistUrl = new URL(auth.playlistUrl, gateway)
  const playlistResponse = await fetch(playlistUrl, { cache: 'no-store', referrerPolicy: 'no-referrer' })
  assert.equal(playlistResponse.status, 200, 'playlist must be available for concurrency probe')
  const line = (await playlistResponse.text()).split(/\r?\n/).find(value => value && !value.startsWith('#'))
  assert.ok(line, 'playlist must reference a media segment')
  const segmentUrl = new URL(line, playlistUrl)
  const results = await Promise.all(Array.from({ length: count }, () => fetch(segmentUrl, { cache: 'no-store', referrerPolicy: 'no-referrer' })))
  const counts = Object.fromEntries([...new Set(results.map(result => result.status))].map(status => [status, results.filter(result => result.status === status).length]))
  await Promise.all(results.map(result => result.body?.cancel()))
  return counts
}

let camera
let broadcast
let browser
try {
  camera = await api('/api/v1/cameras', {
    method: 'POST', expected: 201,
    body: {
      name: 'Chromium broadcast browser E2E source',
      rtspUrl: 'rtsp://mediamtx:8554/test-source',
      rtspUrlFormat: 'STANDARD', videoProcessingMode: 'AUTO', autoStart: false,
    },
  })
  broadcast = await api('/api/v1/broadcasts', {
    method: 'POST', expected: 201, body: { cameraId: camera.id },
  })
  await api(`/api/v1/broadcasts/${broadcast.id}/enabled`, { method: 'PUT', body: { enabled: true } })
  await api(`/api/v1/broadcasts/${broadcast.id}/visibility`, { method: 'PUT', body: { visibility: 'PUBLIC' } })
  const publicId = broadcast.publicId
  const playbackInfo = await api(`/api/v1/broadcasts/${broadcast.id}/playback`)
  assert.equal(playbackInfo.playerUrl, `${player}/watch/${publicId}`, 'watch URL must be stable and shareable')
  assert.ok(playbackInfo.embedHtml.includes(`src="${player}/watch/${publicId}?embed=1"`), 'iframe URL must be stable')
  assert.ok(playbackInfo.embedHtml.includes('referrerpolicy="no-referrer"'), 'generated iframe must suppress referrers')
  const pipelineWaitStarted = Date.now()
  const pipelineReadyAt = await waitForPlaylist(publicId)

  browser = await chromium.launch({ headless: true })
  const context = await browser.newContext({ ignoreHTTPSErrors: false })
  const page = await context.newPage()
  const browserDiagnostics = []
  page.on('console', msg => browserDiagnostics.push(`console:${msg.type()}:${msg.text().replace(/([?&]token=)[^&\s]*/g, '$1[redacted]')}`))
  page.on('pageerror', error => browserDiagnostics.push(`pageerror:${error.message.replace(/([?&]token=)[^&\s]*/g, '$1[redacted]')}`))
  page.on('requestfailed', request => browserDiagnostics.push(`requestfailed:${new URL(request.url()).pathname}:${request.failure()?.errorText ?? 'unknown'}`))
  page.on('response', response => {
    const path = new URL(response.url()).pathname
    if (response.status() >= 400 || path.includes('/broadcast-play/') || path.includes('/api/playback/')) {
      browserDiagnostics.push(`response:${response.status()}:${path}:${response.headers()['content-type'] ?? ''}`)
    }
  })
  let tokenIssues = 0
  let firstAuthorization = null
  let tokenReferrerLeakRequests = 0
  page.on('request', request => {
    if (request.url().includes(`/api/playback/${publicId}/token`)) tokenIssues++
  })
  page.on('response', async response => {
    if (!firstAuthorization && new URL(response.url()).pathname === `/api/playback/${publicId}/token` && response.ok()) {
      firstAuthorization = await response.json().catch(() => null)
    }
  })
  page.on('request', request => {
    const path = new URL(request.url()).pathname
    if (!path.includes('/api/playback/') && !path.includes('/broadcast-play/')) return
    void request.allHeaders().then(headers => {
      if ((headers.referer ?? '').includes('token=')) tokenReferrerLeakRequests++
    })
  })
  await page.goto(playbackInfo.playerUrl, { waitUntil: 'domcontentloaded' })
  const directVideo = page.locator('video')
  let firstDecode
  try { firstDecode = await waitForDecodedVideo(directVideo) }
  catch (error) {
    const metrics = await directVideo.evaluate(v => ({ error: v.error?.code, errorMessage: v.error?.message, networkState: v.networkState, readyState: v.readyState, currentSrc: v.currentSrc.split('?')[0], paused: v.paused, buffered: [...Array(v.buffered.length)].map((_, i) => [v.buffered.start(i), v.buffered.end(i)]) }))
    throw new Error(`${error instanceof Error ? error.message : error}; video=${JSON.stringify(metrics)}; overlay=${await page.getByRole('status').allTextContents()}; browser=${JSON.stringify(browserDiagnostics.slice(-50))}`)
  }
  const firstFrames = firstDecode.frames
  await page.waitForFunction((initial) => {
    const v = document.querySelector('video')
    return v && v.getVideoPlaybackQuality().totalVideoFrames > initial + 10 && v.currentTime > 1
  }, firstFrames, { timeout: 20_000 })

  const embedPage = await context.newPage()
  await embedPage.setContent(playbackInfo.embedHtml)
  const iframe = embedPage.locator('iframe')
  await iframe.waitFor({ state: 'attached' })
  const frame = embedPage.frameLocator('iframe')
  const iframeVideo = frame.locator('video')
  const iframeDecode = await waitForDecodedVideo(iframeVideo)

  const pidsBeforeRenewal = ffmpegPids()
  assert.equal(pidsBeforeRenewal.length, 1, `expected one FFmpeg process for the source, found ${pidsBeforeRenewal.length}`)
  // Token TTL is configured by the caller to 75 seconds for this isolated run.
  // Keep playback alive past expiry; refreshed auth must not restart ingest.
  const playbackBeforeRenewal = await directVideo.evaluate(v => v.currentTime)
  await page.waitForTimeout(90_000)
  await page.waitForFunction(() => {
    const v = document.querySelector('video')
    return v && v.getVideoPlaybackQuality().totalVideoFrames > 20 && v.currentTime > 10
  }, null, { timeout: 15_000 })
  assert.ok(tokenIssues >= 2, `expected token renewal, observed ${tokenIssues} issue(s)`)
  assert.ok(firstAuthorization, 'browser must have received its first short-lived authorization')
  const expiredAuthorizationResponse = await fetch(new URL(firstAuthorization.playlistUrl, gateway), { cache: 'no-store' })
  assert.equal(expiredAuthorizationResponse.status, 401, 'the original expired playback token must be rejected')
  const continued = await directVideo.evaluate(v => ({ frames: v.getVideoPlaybackQuality().totalVideoFrames, currentTime: v.currentTime }))
  assert.ok(continued.frames > firstFrames, 'decoded frames must continue through token renewal')
  assert.ok(continued.currentTime - playbackBeforeRenewal > 60, 'playback time must advance for most of the 90-second renewal interval')
  const pidsAfterRenewal = ffmpegPids()
  assert.deepEqual(pidsAfterRenewal, pidsBeforeRenewal, 'token renewal must not restart the FFmpeg pipeline')

  const statsBeforeLoad = containerStats()
  const loadOutput = execFileSync('python3', [
    '../scripts/broadcast-load-test.py', '--base-url', gateway, '--public-id', publicId,
    '--viewers', '4', '--duration', '30',
  ], { encoding: 'utf8', timeout: 40_000 })
  const fourViewerLoad = JSON.parse(loadOutput)
  assert.equal(fourViewerLoad.failures, 0, 'four-viewer load must have no HTTP failures')
  const statsAfterLoad = containerStats()

  const ffmpegBefore = await fetch(`${gateway}/api/v1/streams/${camera.id}`, {
    headers: { authorization: `Bearer ${managementToken}` },
  })
  assert.ok(ffmpegBefore.ok, 'stream remains active before revocation')

  // Disable revokes the current generation. Playback must move to the offline state.
  await api(`/api/v1/broadcasts/${broadcast.id}/enabled`, { method: 'PUT', body: { enabled: false } })
  await page.getByRole('status').filter({ hasText: /offline|unavailable|expired/i }).waitFor({ timeout: 35_000 })
  const rejected = await fetch(`${gateway}/api/playback/${publicId}/token`)
  assert.ok([404, 409, 410].includes(rejected.status), `disabled token issuance returned ${rejected.status}`)
  await new Promise(resolve => setTimeout(resolve, 250))
  assert.equal(tokenReferrerLeakRequests, 0, 'no media/token request may send a token-bearing Referer')
  assert.equal(logsContain(firstAuthorization.token), false, 'short-lived playback token must not appear in checked container logs')
  for (const key of ['STREAMING_MEDIA_PUBLISH_PASSWORD', 'STREAMING_MEDIA_READ_PASSWORD']) {
    assert.equal(logsContain(process.env[key]), false, `${key} must not appear in checked container logs`)
  }
  await api(`/api/v1/broadcasts/${broadcast.id}/enabled`, { method: 'PUT', body: { enabled: true } })
  await waitForPlaylist(publicId)
  const concurrentLimitStatuses = {}
  for (const count of [32, 33, 96]) concurrentLimitStatuses[count] = await concurrencyProbe(publicId, count)
  console.log(JSON.stringify({
    result: 'PASS', browser: 'Chromium', directDecode: firstDecode,
    iframeDecode, renewalTokenRequests: tokenIssues, continuedPlayback: continued,
    renewalWindowPlaybackAdvanceSeconds: Math.round(continued.currentTime - playbackBeforeRenewal),
    fourViewerLoad, concurrentLimitProbe: Object.fromEntries(Object.entries(concurrentLimitStatuses).map(([count, statuses]) => [count, { statusCounts: statuses, observed429: Number(statuses['429'] ?? 0) > 0 }])),
    containerStatsBeforeLoad: statsBeforeLoad, containerStatsAfterLoad: statsAfterLoad,
    ffmpegPidsStableThroughRenewal: pidsBeforeRenewal,
    expiredOriginalTokenStatus: expiredAuthorizationResponse.status,
    permanentWatchAndEmbed: 'PASS', noReferrerTokenLeak: tokenReferrerLeakRequests === 0,
    playbackAndMediaCredentialsInCheckedLogs: false,
    pipelineReadySeconds: Math.round((pipelineReadyAt - pipelineWaitStarted) / 1000),
    revocation: 'offline UI and token renewal rejected after disable',
  }))
} catch (error) {
  console.error(error instanceof Error ? error.message : String(error))
  process.exitCode = 1
} finally {
  await browser?.close()
  if (broadcast) {
    await api(`/api/v1/broadcasts/${broadcast.id}`, { method: 'DELETE', expected: 204 }).catch(() => {})
  }
  if (camera) {
    const response = await fetch(`${gateway}/api/v1/cameras/${camera.id}`, { method: 'DELETE' }).catch(() => null)
    if (response && !response.ok && response.status !== 404) console.error(`test camera cleanup returned ${response.status}`)
  }
}
