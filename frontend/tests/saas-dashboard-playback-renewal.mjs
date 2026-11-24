import { chromium } from 'playwright'
import { execFileSync } from 'node:child_process'
import { readFile, writeFile } from 'node:fs/promises'

const APP = 'http://localhost:5173'
const GATEWAY = 'http://localhost:8081'
const HLS_DIR = '/tmp/phase33b1-hls'
const broadcast = {
  publicId: 'd2d387c8-5010-4937-9f51-d62ee09cc62d',
  tenantId: 'b23dc4d7-070e-4e1c-b8e4-24b54c2b68fe',
}
const owner = { username: 'dashboard-owner-it', password: process.env.DASHBOARD_OWNER_PASSWORD }

function check(value, message) {
  if (!value) throw new Error(message)
  console.log(`PASS ${message}`)
}

function createLongSyntheticHls() {
  execFileSync('ffmpeg', [
    '-hide_banner', '-loglevel', 'error', '-y',
    '-f', 'lavfi', '-i', 'testsrc2=size=320x180:rate=10',
    '-t', '140', '-c:v', 'libx264', '-threads', '1', '-preset', 'ultrafast', '-tune', 'zerolatency',
    '-pix_fmt', 'yuv420p', '-g', '20', '-sc_threshold', '0', '-an',
    '-f', 'hls', '-hls_time', '2', '-hls_list_size', '0', '-hls_playlist_type', 'vod',
    '-hls_segment_filename', `${HLS_DIR}/phase33b2-renewal-%03d.ts`,
    `${HLS_DIR}/index.m3u8`,
  ], { stdio: 'ignore', timeout: 60000 })
}

async function main() {
  const playlistPath = `${HLS_DIR}/index.m3u8`
  const originalPlaylist = await readFile(playlistPath)
  let playlistRestored = false
  const browser = await chromium.launch({
    headless: true,
    executablePath: process.env.CHROMIUM_PATH || '/home/tatarino/.cache/ms-playwright/chromium-1255/chrome-linux64/chrome',
    args: ['--no-sandbox'],
  })
  const context = await browser.newContext()
  const page = await context.newPage()
  const grantTokens = new Set()
  const grantRecords = []
  const grantRecordTasks = []
  const mediaStatuses = []
  const tokenStatuses = []
  const playlistStats = []
  const playlistTasks = []
  page.on('response', response => {
    const url = response.url()
    if (url.includes('/api/playback/')) {
      tokenStatuses.push(response.status())
      if (response.ok()) {
        grantRecordTasks.push(response.json().then(result => {
          if (result?.token) {
            grantTokens.add(result.token)
            grantRecords.push({ token: result.token, playlistUrl: result.playlistUrl, expiresAt: result.expiresAt })
          }
        }).catch(() => {}))
      }
    }
    if (url.includes('/broadcast-play/')) {
      mediaStatuses.push(response.status())
      if (url.includes('.m3u8') && response.ok()) {
        playlistTasks.push(response.text().then(text => {
          const durations = [...text.matchAll(/^#EXTINF:([0-9.]+)/gm)].map(match => Number(match[1]))
          playlistStats.push({ segments: durations.length, duration: durations.reduce((a, b) => a + b, 0), endList: text.includes('#EXT-X-ENDLIST') })
        }).catch(() => {}))
      }
    }
  })

  try {
    createLongSyntheticHls()
    await page.goto(`${APP}/app`)
    const authState = await Promise.race([
      page.locator('#username').waitFor({ timeout: 15000 }).then(() => 'login').catch(() => null),
      page.locator('.saas-shell').waitFor({ timeout: 15000 }).then(() => 'shell').catch(() => null),
    ])
    if (authState === 'login') {
      await page.locator('#username').fill(owner.username)
      await page.locator('#password').fill(owner.password)
      await page.locator('#kc-login').click()
    }
    await page.locator('.saas-shell').waitFor({ timeout: 30000 })
    await page.getByText('Loading your workspace…').waitFor({ state: 'detached', timeout: 30000 }).catch(() => {})
    await page.locator('#org-select').selectOption(broadcast.tenantId)
    await page.goto(`${APP}/watch/${broadcast.publicId}?tenant=${broadcast.tenantId}`)
    await page.locator('.player-page').waitFor({ timeout: 30000 })
    console.log('OBSERVED private watch player mounted; waiting for HLS decode and grant renewal')
    try {
      await page.waitForFunction(() => {
        const video = document.querySelector('video')
        return video && video.videoWidth > 0 && video.currentTime >= 40
      }, null, { timeout: 90000 })
    } catch (error) {
      const diagnostic = await page.evaluate(() => ({
        host: location.host,
        path: location.pathname,
        player: Boolean(document.querySelector('.player-page')),
        videoWidth: document.querySelector('video')?.videoWidth ?? 0,
        currentTime: document.querySelector('video')?.currentTime ?? 0,
        duration: document.querySelector('video')?.duration ?? 0,
        buffered: document.querySelector('video') ? Array.from({ length: document.querySelector('video').buffered.length }, (_, i) => [document.querySelector('video').buffered.start(i), document.querySelector('video').buffered.end(i)]) : [],
        statusText: document.querySelector('[role=status]')?.textContent ?? '',
      })).catch(() => null)
      await Promise.all([...grantRecordTasks, ...playlistTasks])
      console.log('PLAYBACK_DIAGNOSTIC', JSON.stringify({ diagnostic, tokenStatuses, mediaStatuses, grants: grantTokens.size }))
      throw error
    }
    await Promise.all([...grantRecordTasks, ...playlistTasks])
    const playingTime = await page.locator('video').evaluate(video => video.currentTime)
    check(playingTime >= 40 && grantTokens.size >= 2,
      'PRIVATE HLS video kept decoding beyond initial short grant and obtained a renewed distinct grant')
    check(tokenStatuses.includes(200) && mediaStatuses.length > 0 && mediaStatuses.every(status => status === 200),
      'renewal token and all observed protected playlist/segment requests returned HTTP 200')

    const firstGrant = grantRecords[0]
    if (!firstGrant) throw new Error('no successful private playback grant was captured')
    const firstExpiry = Date.parse(firstGrant.expiresAt)
    console.log(`OBSERVED first playback grant lifetime ${Math.round((firstExpiry - Date.now()) / 1000)}s remaining at renewal check; grants=${grantTokens.size}, playlist=${JSON.stringify(playlistStats)}`)
    const remaining = Math.max(0, firstExpiry + 1500 - Date.now())
    if (remaining > 120000) throw new Error('unexpectedly long grant expiry wait')
    await new Promise(resolve => setTimeout(resolve, remaining))
    const expiredMediaUrl = new URL(firstGrant.playlistUrl, GATEWAY)
    const expiredResponse = await fetch(expiredMediaUrl, { redirect: 'manual' })
    check(expiredResponse.status === 401 || expiredResponse.status === 403,
      `expired playback grant rejected with HTTP ${expiredResponse.status}`)
    const continuedTime = await page.locator('video').evaluate(video => video.currentTime)
    check(continuedTime > 80, 'authorized HLS playback continued while grants were renewed')
    console.log('SUMMARY short-lived grant renewal and expired-grant rejection completed; grant values were kept in memory only')
  } finally {
    await context.close().catch(() => {})
    await browser.close().catch(() => {})
    await writeFile(playlistPath, originalPlaylist)
    playlistRestored = true
    if (playlistRestored) console.log('RESTORED original synthetic HLS fixture playlist')
  }
}

main().catch(error => {
  const message = String(error?.message || 'Playback renewal acceptance failed')
    .replace(/([?#])[^\s]*/g, '$1[redacted]')
    .replace(/Bearer\s+\S+/g, 'Bearer [redacted]')
  console.error(`FAIL ${message}`)
  process.exitCode = 1
})
