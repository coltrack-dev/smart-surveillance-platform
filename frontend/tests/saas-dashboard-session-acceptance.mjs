import { chromium } from 'playwright'

const APP = 'http://localhost:5173'
const GATEWAY = 'http://localhost:8081'
const KEYCLOAK = 'http://localhost:18180'
const owner = { username: 'dashboard-owner-it', password: process.env.DASHBOARD_OWNER_PASSWORD }
const testAccessTokenLifespan = 45

function check(value, message) {
  if (!value) throw new Error(message)
  console.log(`PASS ${message}`)
}

function tokenExpiry(token) {
  const claims = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString())
  return { exp: Number(claims.exp), lifetime: Number(claims.exp) - Number(claims.iat) }
}

async function adminAccessToken() {
  const form = new URLSearchParams({
    grant_type: 'password',
    client_id: 'admin-cli',
    username: process.env.DASHBOARD_IT_KEYCLOAK_ADMIN_USER,
    password: process.env.DASHBOARD_IT_KEYCLOAK_ADMIN_PASSWORD,
  })
  const response = await fetch(`${KEYCLOAK}/realms/master/protocol/openid-connect/token`, {
    method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' }, body: form,
  })
  if (!response.ok) throw new Error(`isolated Keycloak admin auth HTTP ${response.status}`)
  return (await response.json()).access_token
}

async function getRealm(token) {
  const response = await fetch(`${KEYCLOAK}/admin/realms/streaming`, { headers: { authorization: `Bearer ${token}` } })
  if (!response.ok) throw new Error(`isolated realm read HTTP ${response.status}`)
  return response.json()
}

async function putRealm(token, realm) {
  const response = await fetch(`${KEYCLOAK}/admin/realms/streaming`, {
    method: 'PUT', headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' }, body: JSON.stringify(realm),
  })
  if (!response.ok) throw new Error(`isolated realm update HTTP ${response.status}`)
}

async function logoutSyntheticUser(token) {
  const url = new URL(`${KEYCLOAK}/admin/realms/streaming/users`)
  url.searchParams.set('username', owner.username)
  url.searchParams.set('exact', 'true')
  const usersResponse = await fetch(url, { headers: { authorization: `Bearer ${token}` } })
  if (!usersResponse.ok) throw new Error(`isolated synthetic-user lookup HTTP ${usersResponse.status}`)
  const users = await usersResponse.json()
  if (users.length !== 1) throw new Error('expected exactly one isolated synthetic OWNER user')
  const response = await fetch(`${KEYCLOAK}/admin/realms/streaming/users/${users[0].id}/logout`, {
    method: 'POST', headers: { authorization: `Bearer ${token}` },
  })
  if (!response.ok) throw new Error(`isolated synthetic-user logout HTTP ${response.status}`)
}

async function login(page) {
  await page.goto(`${APP}/app`)
  if (page.url().includes('/protocol/openid-connect/auth')) {
    await page.locator('#username').fill(owner.username)
    await page.locator('#password').fill(owner.password)
    await page.locator('#kc-login').click()
  }
  await page.locator('.saas-shell').waitFor({ timeout: 30000 })
  await page.getByText('Loading your workspace…').waitFor({ state: 'detached', timeout: 30000 }).catch(() => {})
}

async function waitUntilExpired(exp) {
  const waitMs = Math.max(0, exp * 1000 + 1200 - Date.now())
  if (waitMs > 90000) throw new Error('unexpectedly long synthetic token expiration wait')
  await new Promise(resolve => setTimeout(resolve, waitMs))
}

async function refreshCameraList(page) {
  await page.getByRole('link', { name: 'Cameras' }).click()
  await page.locator('.saas-shell').waitFor()
  const responsePromise = page.waitForResponse(response =>
    response.url().includes('/api/v1/cameras') && response.request().method() === 'GET', { timeout: 25000 },
  ).catch(() => null)
  await page.getByRole('button', { name: 'Refresh' }).click()
  return responsePromise
}

async function main() {
  const adminToken = await adminAccessToken()
  const originalRealm = await getRealm(adminToken)
  const originalAccessTokenLifespan = originalRealm.accessTokenLifespan
  let realmChanged = false
  const browser = await chromium.launch({
    headless: true,
    executablePath: process.env.CHROMIUM_PATH || '/home/tatarino/.cache/ms-playwright/chromium-1255/chrome-linux64/chrome',
    args: ['--no-sandbox'],
  })
  const context = await browser.newContext()
  const page = await context.newPage()
  const gatewayTokens = []
  const gatewayResponses = []
  const authNavigations = []
  page.on('request', request => {
    if (request.url().startsWith(`${GATEWAY}/api/`)) {
      const authorization = request.headers().authorization ?? ''
      if (authorization.startsWith('Bearer ')) gatewayTokens.push(authorization.slice(7))
    }
  })
  page.on('response', response => {
    if (response.url().startsWith(`${GATEWAY}/api/`)) gatewayResponses.push(response.status())
  })
  page.on('framenavigated', frame => {
    if (frame === page.mainFrame() && frame.url().includes('/protocol/openid-connect/auth')) authNavigations.push(frame.url())
  })

  try {
    const shortRealm = { ...originalRealm, accessTokenLifespan: testAccessTokenLifespan }
    await putRealm(adminToken, shortRealm)
    realmChanged = true

    await login(page)
    const initialToken = gatewayTokens.at(-1)
    if (!initialToken) throw new Error('no authenticated Gateway request was observed')
    const initial = tokenExpiry(initialToken)
    check(initial.lifetime <= testAccessTokenLifespan + 2, `OIDC token uses isolated ${testAccessTokenLifespan}s test lifespan`)

    await waitUntilExpired(initial.exp)
    const beforeRefreshCount = gatewayTokens.length
    const camerasResponse = await refreshCameraList(page)
    const refreshedToken = gatewayTokens.slice(beforeRefreshCount).find(token => token !== initialToken)
    check(camerasResponse?.status() === 200 && Boolean(refreshedToken),
      'expired access token renewed successfully and protected Camera management returned HTTP 200')

    await page.getByRole('button', { name: 'Sign out' }).click()
    await page.goto(`${APP}/app`)
    await page.locator('#username').waitFor({ timeout: 20000 })
    check(await page.locator('.saas-shell').count() === 0, 'logout invalidated the browser session and protected /app requires sign-in')
    await page.waitForTimeout(2500)
    check(await page.locator('#username').count() === 1 && authNavigations.length < 4,
      'logout flow remains on one Keycloak login prompt without redirect loop')

    await page.locator('#username').fill(owner.username)
    await page.locator('#password').fill(owner.password)
    await page.locator('#kc-login').click()
    await page.locator('.saas-shell').waitFor({ timeout: 30000 })
    await page.getByText('Loading your workspace…').waitFor({ state: 'detached', timeout: 30000 }).catch(() => {})
    const tokenForRevokedSession = gatewayTokens.at(-1)
    if (!tokenForRevokedSession) throw new Error('no Gateway token observed for session invalidation check')
    const revokedSessionClaims = tokenExpiry(tokenForRevokedSession)
    await logoutSyntheticUser(adminToken)
    await waitUntilExpired(revokedSessionClaims.exp)

    const responseStart = gatewayResponses.length
    const tokenStart = gatewayTokens.length
    const navigationStart = authNavigations.length
    const failedRenewalResponse = await refreshCameraList(page)
    await page.locator('#username').waitFor({ timeout: 20000 })
    await page.waitForTimeout(2500)
    const failedResponses = gatewayResponses.slice(responseStart)
    check(!failedRenewalResponse || failedRenewalResponse.status() >= 400,
      'management request did not succeed after the Keycloak session was invalidated')
    check(!gatewayTokens.slice(tokenStart).some(token => token !== tokenForRevokedSession),
      'invalidated refresh session did not produce a replacement access token')
    check(await page.locator('#username').count() === 1 && authNavigations.length - navigationStart <= 2,
      'failed renewal returns to one login prompt without redirect loop')
    check(failedResponses.every(status => status >= 400),
      'management requests after failed renewal were rejected or aborted, never accepted')
    console.log('SUMMARY short-lived OIDC renewal, logout and revoked-session fail-safe checks completed')
  } finally {
    await context.close().catch(() => {})
    await browser.close().catch(() => {})
    if (realmChanged) {
      const restoreToken = await adminAccessToken()
      await putRealm(restoreToken, originalRealm)
      console.log(`RESTORED isolated Keycloak access-token lifespan to ${originalAccessTokenLifespan}s`)
    }
  }
}

main().catch(error => {
  const message = String(error?.message || 'OIDC session acceptance failed')
    .replace(/([?#])[^\s]*/g, '$1[redacted]')
    .replace(/Bearer\s+\S+/g, 'Bearer [redacted]')
  console.error(`FAIL ${message}`)
  process.exitCode = 1
})
