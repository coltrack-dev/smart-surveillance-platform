import { chromium } from 'playwright'

const LEGACY = 'http://localhost:5175'

function check(value, message) {
  if (!value) throw new Error(message)
  console.log(`PASS ${message}`)
}

async function main() {
  const browser = await chromium.launch({
    headless: true,
    executablePath: process.env.CHROMIUM_PATH || '/home/tatarino/.cache/ms-playwright/chromium-1255/chrome-linux64/chrome',
    args: ['--no-sandbox'],
  })
  try {
    const page = await browser.newPage()
    const keycloakRequests = []
    const pageErrors = []
    const moduleResponses = []
    const requestPaths = []
    page.on('request', request => {
      if (request.url().startsWith(`${LEGACY}/`)) requestPaths.push(new URL(request.url()).pathname)
      if (request.url().includes('/realms/streaming/')) keycloakRequests.push(request.url())
    })
    page.on('pageerror', error => pageErrors.push(error.message))
    page.on('console', message => { if (message.type() === 'error') pageErrors.push(message.text()) })
    page.on('response', response => {
      if (response.url().includes('/src/') || response.url().includes('/node_modules/')) moduleResponses.push(response.status())
    })
    await page.route('http://localhost:8081/**', route => route.abort())

    await page.goto(`${LEGACY}/`)
    await page.waitForTimeout(1000)
    console.log('LEGACY_ROOT_OBSERVED', JSON.stringify({
      host: new URL(page.url()).host,
      path: new URL(page.url()).pathname,
      headerCount: await page.locator('.header').count(),
      bodyText: (await page.locator('body').innerText()).slice(0, 100),
      pageErrors,
      requestPaths: [...new Set(requestPaths)],
    }))
    await page.locator('.header').waitFor({ timeout: 10000 })
    check(new URL(page.url()).pathname === '/', 'legacy root route loads the existing Surveillance application shell')

    await page.goto(`${LEGACY}/app`)
    await page.waitForTimeout(1000)
    console.log('LEGACY_ROUTE_OBSERVED', JSON.stringify({
      host: new URL(page.url()).host,
      path: new URL(page.url()).pathname,
      shellCount: await page.locator('.saas-shell').count(),
      keycloakRequestCount: keycloakRequests.length,
      bodyText: (await page.locator('body').innerText()).slice(0, 100),
      pageErrors,
      failedModuleStatuses: moduleResponses.filter(status => status >= 400),
      requestPaths: [...new Set(requestPaths)],
      appHtmlLength: await page.locator('#app').evaluate(element => element.innerHTML.length).catch(() => -1),
    }))
    if (new URL(page.url()).pathname !== '/') {
      await page.waitForURL(url => url.origin === LEGACY && url.pathname === '/', { timeout: 10000 })
    }
    check(await page.locator('.saas-shell').count() === 0, 'legacy /app redirects to / without SaaS shell')
    check(keycloakRequests.length === 0, 'legacy frontend does not request Keycloak')

    await page.goto(`${LEGACY}/watch/00000000-0000-4000-8000-000000000001`)
    await page.locator('.player-page').waitFor({ timeout: 10000 })
    check(new URL(page.url()).pathname.startsWith('/watch/') && await page.locator('video').count() === 1,
      'legacy public watch route renders the player without requiring Keycloak')
    check(keycloakRequests.length === 0, 'legacy public watch remains OIDC-independent')
  } finally {
    await browser.close()
  }
}

main().catch(error => {
  const message = String(error?.message || 'Legacy compatibility acceptance failed')
    .replace(/([?#])[^\s]*/g, '$1[redacted]')
  console.error(`FAIL ${message}`)
  process.exitCode = 1
})
