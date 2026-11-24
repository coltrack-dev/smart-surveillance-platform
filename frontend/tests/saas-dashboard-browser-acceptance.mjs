import { chromium } from 'playwright'

const APP = 'http://localhost:5173'
const KEYCLOAK = 'http://localhost:18180'
const GATEWAY = 'http://localhost:8081/api'
const realm = 'streaming'
const users = [
  { username: 'dashboard-owner-it', email: 'dashboard-owner@example.test', password: process.env.DASHBOARD_OWNER_PASSWORD, firstName: 'Dashboard', lastName: 'Owner' },
  { username: 'dashboard-viewer-it', email: 'dashboard-viewer@example.test', password: process.env.DASHBOARD_VIEWER_PASSWORD, firstName: 'Dashboard', lastName: 'Viewer' },
  { username: 'dashboard-mismatch-it', email: 'dashboard-mismatch@example.test', password: process.env.DASHBOARD_MISMATCH_PASSWORD, firstName: 'Dashboard', lastName: 'Mismatch' },
  { username: 'dashboard-expired-it', email: 'dashboard-expired@example.test', password: process.env.DASHBOARD_EXPIRED_PASSWORD, firstName: 'Dashboard', lastName: 'Expired' },
  { username: 'dashboard-revoked-it', email: 'dashboard-revoked@example.test', password: process.env.DASHBOARD_REVOKED_PASSWORD, firstName: 'Dashboard', lastName: 'Revoked' },
]

const passed = []
let ownerAccessToken = ''
let viewerAccessToken = ''
let tenantA = ''
let tenantB = ''
let cameraA = ''
let cameraB = ''
let broadcastA = null
let broadcastB = null

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

function record(message) {
  passed.push(message)
  console.log(`PASS ${message}`)
}

async function keycloakAdminToken() {
  const response = await fetch(`${KEYCLOAK}/realms/master/protocol/openid-connect/token`, {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      username: process.env.DASHBOARD_IT_KEYCLOAK_ADMIN_USER,
      password: process.env.DASHBOARD_IT_KEYCLOAK_ADMIN_PASSWORD,
      grant_type: 'password',
      client_id: 'admin-cli',
    }),
  })
  assert(response.ok, 'isolated Keycloak admin token available')
  return (await response.json()).access_token
}

async function createSyntheticUsers() {
  assert(users.every(user => user.password), 'synthetic browser-user passwords provided')
  const admin = await keycloakAdminToken()
  for (const user of users) {
    const headers = { authorization: `Bearer ${admin}`, 'content-type': 'application/json' }
    const lookup = await fetch(`${KEYCLOAK}/admin/realms/${realm}/users?username=${encodeURIComponent(user.username)}&exact=true`, { headers })
    assert(lookup.ok, 'synthetic users can be provisioned in isolated realm')
    const existing = await lookup.json()
    const body = {
      username: user.username, email: user.email, firstName: user.firstName, lastName: user.lastName,
      enabled: true, emailVerified: true,
      credentials: [{ type: 'password', value: user.password, temporary: false }],
    }
    if (existing.length) {
      await fetch(`${KEYCLOAK}/admin/realms/${realm}/users/${existing[0].id}`, {
        method: 'PUT', headers, body: JSON.stringify(body),
      }).then(response => assert(response.ok, 'synthetic user credentials reset'))
    } else {
      const created = await fetch(`${KEYCLOAK}/admin/realms/${realm}/users`, { method: 'POST', headers, body: JSON.stringify(body) })
      assert(created.status === 201, 'synthetic users created')
    }
  }
  record('Isolated Keycloak synthetic identities provisioned (no real users changed)')
}

async function waitForWorkspace(page) {
  await page.locator('.saas-shell').waitFor({ state: 'visible', timeout: 30000 })
  await page.getByText('Loading your workspace…').waitFor({ state: 'detached', timeout: 30000 }).catch(() => {})
  await page.waitForTimeout(250)
}

async function login(browser, username, password, path = '/app') {
  const context = await browser.newContext({ permissions: ['clipboard-read', 'clipboard-write'] })
  const page = await context.newPage()
  page.on('request', request => {
    if (request.url().startsWith('http://localhost:8081/') && request.headers().authorization?.startsWith('Bearer ')) {
      const token = request.headers().authorization.slice('Bearer '.length)
      if (username === 'dashboard-owner-it') ownerAccessToken = token
      if (username === 'dashboard-viewer-it') viewerAccessToken = token
    }
  })
  await page.goto(`${APP}${path}`)
  if (page.url().includes('/protocol/openid-connect/auth')) {
    await page.locator('#username').fill(username)
    await page.locator('#password').fill(password)
    await page.locator('#kc-login').click()
  }
  await page.waitForURL(url => url.origin === APP && (url.pathname.startsWith('/app') || url.pathname === '/invite'), { timeout: 30000 })
  await waitForWorkspace(page)
  return { context, page }
}

async function addCamera(page, name, pass) {
  await page.getByRole('link', { name: 'Cameras' }).click()
  await page.getByPlaceholder('Front entrance').fill(name)
  await page.getByPlaceholder('rtsp://host:554/path').fill('rtsp://127.0.0.1:65534/synthetic')
  await page.getByLabel('RTSP username').fill('synthetic-user')
  await page.getByLabel('RTSP password').fill(pass)
  const responsePromise = page.waitForResponse(response => response.url().includes('/api/v1/cameras') && response.request().method() === 'POST')
  await page.getByRole('button', { name: 'Add camera' }).click()
  const response = await responsePromise
  assert(response.ok(), `Camera creation returned ${response.status()}`)
  const created = await response.json()
  assert(created.id, 'Camera API returned its new ID')
  const safeListPromise = page.waitForResponse(response => response.url().includes('/api/v1/cameras') && response.request().method() === 'GET')
  await page.getByRole('button', { name: 'Refresh' }).click()
  const safeResponse = await safeListPromise
  const safeText = JSON.stringify(await safeResponse.json())
  assert(!safeText.includes(pass) && !safeText.includes('rtspPassword') && !safeText.includes('rtspUrl'), 'Camera list excludes stored connection credentials')
  assert(!(await page.locator('body').innerText()).includes(pass), 'Camera UI does not render RTSP credentials')
  return created
}

async function createBroadcast(page, cameraId, visibility = 'PRIVATE') {
  await page.getByRole('link', { name: 'Broadcasts' }).click()
  await page.locator('.stack > .panel').first().getByLabel('Camera').selectOption(cameraId)
  await page.locator('.stack > .panel').first().getByLabel('Visibility').selectOption(visibility)
  const responsePromise = page.waitForResponse(response => response.url().includes('/api/v1/broadcasts') && response.request().method() === 'POST')
  await page.getByRole('button', { name: 'Create broadcast' }).click()
  const response = await responsePromise
  assert(response.ok(), `Broadcast creation returned ${response.status()}`)
  const created = await response.json()
  assert(created.id && created.publicId, 'Broadcast API returned permanent identifiers')
  return created
}

async function setVisibility(page, broadcast, visibility) {
  const card = page.locator('.broadcast-card').filter({ hasText: broadcast.publicId })
  const select = card.locator('.visibility-select select')
  if (await select.inputValue() !== visibility) await select.selectOption(visibility)
  await page.waitForTimeout(250)
}

async function enableBroadcast(page, broadcast) {
  const card = page.locator('.broadcast-card').filter({ hasText: broadcast.publicId })
  if (await card.getByRole('button', { name: 'Enable' }).count()) {
    const result = page.waitForResponse(response => response.url().includes(`/api/v1/broadcasts/${broadcast.id}/enabled`))
    await card.getByRole('button', { name: 'Enable' }).click()
    const response = await result
    assert(response.ok(), `Broadcast enable returned ${response.status()}`)
  }
  await card.locator('.status-tag').filter({ hasText: 'LIVE' }).waitFor({ timeout: 10000 })
}

async function invite(page, email, role = 'VIEWER') {
  await page.getByRole('link', { name: 'Members & invites' }).click()
  const form = page.locator('form.inline-form').filter({ has: page.getByPlaceholder('name@example.com') })
  await form.getByPlaceholder('name@example.com').fill(email)
  await form.locator('select').selectOption(role)
  const responsePromise = page.waitForResponse(response => response.url().includes('/invitations') && response.request().method() === 'POST')
  await form.getByRole('button', { name: 'Create invitation' }).click()
  const response = await responsePromise
  assert(response.ok(), `Invitation creation returned ${response.status()}`)
  const notice = await page.getByRole('status').innerText()
  const match = notice.match(/https?:\/\/localhost:5173\/invite#token=([^\s]+)/)
  assert(match, 'Development invitation URL is shown once to its authorized creator')
  return `http://localhost:5173/invite#token=${match[1]}`
}

async function acceptInvitation(browser, username, password, url, expectedStatus) {
  const { context, page } = await login(browser, username, password, url.replace(APP, ''))
  const responsePromise = page.waitForResponse(response => response.url().includes('/api/v1/invitations/accept'))
  await page.getByRole('button', { name: 'Accept invitation' }).click()
  const response = await responsePromise
  assert(response.status() === expectedStatus, `Invitation acceptance returned ${response.status()}, expected ${expectedStatus}`)
  if (expectedStatus === 200) await page.getByRole('status').filter({ hasText: 'Invitation accepted.' }).waitFor()
  await context.close()
  return response.status()
}

async function apiRequest(method, path, token, tenantId, body) {
  return fetch(`${GATEWAY}${path}`, {
    method,
    headers: {
      authorization: `Bearer ${token}`,
      'X-Tenant-ID': tenantId,
      ...(body ? { 'content-type': 'application/json' } : {}),
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  })
}

async function main() {
  await createSyntheticUsers()
  const browser = await chromium.launch({
    headless: true,
    executablePath: process.env.CHROMIUM_PATH || '/home/tatarino/.cache/ms-playwright/chromium-1255/chrome-linux64/chrome',
    args: ['--no-sandbox'],
  })
  const contexts = []
  try {
    const anonymous = await browser.newPage()
    await anonymous.goto(`${APP}/app`)
    assert(anonymous.url().includes('/protocol/openid-connect/auth'), 'anonymous SaaS route redirects to OIDC')
    record('Anonymous /app route requires OIDC login')
    await anonymous.close()

    const owner = await login(browser, users[0].username, users[0].password)
    contexts.push(owner.context)
    assert(owner.page.url().startsWith(`${APP}/app`), 'PKCE callback returns to the protected SaaS app')
    assert(await owner.page.locator('.saas-shell').count() === 1, 'authenticated browser renders SaaS shell')
    assert(!(await owner.page.evaluate(() => Object.keys(localStorage).join(' '))).match(/token|auth/i), 'OIDC tokens are not stored in localStorage')
    await owner.page.reload()
    await waitForWorkspace(owner.page)
    assert(owner.page.locator('.saas-shell').isVisible(), 'OIDC session persists across reload')
    record('Authorization Code + PKCE login, callback, protected route, reload persistence, no localStorage token')

    await owner.page.getByPlaceholder('Organization name').fill('Dashboard Acceptance Alpha')
    await owner.page.getByRole('button', { name: 'Create organization' }).click()
    await owner.page.getByRole('status').filter({ hasText: 'You are its OWNER' }).waitFor()
    tenantA = await owner.page.locator('#org-select').inputValue()
    assert(tenantA, 'first organization selected')
    assert(await owner.page.locator('.role-pill').first().innerText() === 'OWNER', 'creator receives OWNER role')
    owner.page.once('dialog', dialog => dialog.accept('Dashboard Alpha Renamed'))
    await owner.page.getByRole('button', { name: 'Rename organization' }).click()
    await owner.page.locator('.saas-topbar .eyebrow').filter({ hasText: 'Dashboard Alpha Renamed' }).waitFor()
    record('Organization creation, automatic OWNER assignment, and rename')

    const cameraAResult = await addCamera(owner.page, 'Acceptance Camera Alpha', 'synthetic-camera-secret-alpha')
    cameraA = cameraAResult.id
    await owner.page.getByRole('button', { name: 'Edit' }).first().click()
    assert(await owner.page.getByLabel('New RTSP URL').inputValue() === '', 'camera edit does not prefill stored RTSP URL')
    await owner.page.getByLabel('Camera name').last().fill('Acceptance Camera Alpha Edited')
    await owner.page.getByLabel('New RTSP URL').fill('rtsp://127.0.0.1:65534/synthetic-edited')
    await owner.page.getByRole('button', { name: 'Save camera' }).click()
    await owner.page.getByText('Acceptance Camera Alpha Edited').waitFor()
    record('Camera create/edit, tenant-scoped list, and RTSP credential non-disclosure')

    owner.page.once('dialog', dialog => dialog.accept('Dashboard Acceptance Beta'))
    await owner.page.getByRole('button', { name: '＋ New organization' }).click()
    await owner.page.getByRole('status').filter({ hasText: 'You are its OWNER' }).waitFor()
    tenantB = await owner.page.locator('#org-select').inputValue()
    assert(tenantB && tenantB !== tenantA, 'same user can belong to multiple organizations')
    assert(await owner.page.getByText('No cameras in this workspace yet.').count() === 1, 'switching organizations clears stale camera data')
    cameraB = (await addCamera(owner.page, 'Acceptance Camera Beta', 'synthetic-camera-secret-beta')).id
    await owner.page.locator('#org-select').selectOption(tenantA)
    await owner.page.getByText('Acceptance Camera Alpha Edited').waitFor()
    assert(await owner.page.getByText('Acceptance Camera Beta').count() === 0, 'tenant switch does not leak another camera')
    await owner.page.locator('#org-select').selectOption(tenantB)
    await owner.page.getByText('No cameras in this workspace yet.').waitFor()
    assert(await owner.page.getByText('Acceptance Camera Alpha Edited').count() === 0, 'tenant switch removes stale camera rows')
    record('Second organization selection and camera data isolation on tenant switch')

    await owner.page.getByRole('link', { name: 'Broadcasts' }).click()
    broadcastB = await createBroadcast(owner.page, cameraB, 'PRIVATE')
    await enableBroadcast(owner.page, broadcastB)
    record('Broadcast creation and enablement in second organization')
    await owner.page.locator('#org-select').selectOption(tenantA)
    broadcastA = await createBroadcast(owner.page, cameraA, 'PRIVATE')
    await enableBroadcast(owner.page, broadcastA)
    await setVisibility(owner.page, broadcastA, 'PUBLIC')

    const cardA = owner.page.locator('.broadcast-card').filter({ hasText: broadcastA.publicId })
    const watchHref = await cardA.getByRole('link', { name: 'Open watch page' }).getAttribute('href')
    assert(watchHref && watchHref.startsWith(`/watch/${broadcastA.publicId}`) && !watchHref.includes('token='), 'watch link is permanent and contains no playback grant')
    await cardA.getByRole('button', { name: 'Copy iframe HTML' }).click()
    const embed = await owner.page.evaluate(() => navigator.clipboard.readText())
    assert(embed.includes(`<iframe src="http://localhost:5173/watch/${broadcastA.publicId}`) && embed.includes('referrerpolicy="no-referrer"') && !embed.includes('token='), 'iframe HTML uses permanent watch URL without grant')
    record('Broadcast visibility, permanent watch URL, and stable iframe HTML')

    const invitationUrl = await invite(owner.page, users[1].email, 'VIEWER')
    await acceptInvitation(browser, users[2].username, users[2].password, invitationUrl, 403)
    record('Invitation with mismatched verified email rejected (403)')
    await acceptInvitation(browser, users[1].username, users[1].password, invitationUrl, 200)
    record('Matching verified-email invitation acceptance (single-use success)')

    const expiredUrl = await invite(owner.page, users[3].email, 'VIEWER')
    const expiredToken = new URL(expiredUrl).hash.slice('#token='.length)
    const expiredLookup = await fetch(`${GATEWAY}/v1/organizations/${tenantA}/invitations`, { headers: { authorization: `Bearer ${ownerAccessToken}` } })
    assert(expiredLookup.ok, 'owner can list pending invitations')
    const expiredInvitation = (await expiredLookup.json()).find(item => item.email === users[3].email)
    assert(expiredInvitation?.id, 'expiry invitation record found')
    const expireResult = await fetch(`${GATEWAY}/_test/expire-invitation/${expiredInvitation.id}`, { method: 'POST' }).catch(() => null)
    assert(!expireResult || expireResult.status === 404, 'no test-only production endpoint is exposed')
    // The acceptance harness expires only its synthetic invitation directly in its isolated database.
    const expireCommand = `docker exec phase33b1-saas-acceptance-dashboard-it-postgres-1 psql -U streaming -d streaming -v ON_ERROR_STOP=1 -c "UPDATE identity.tenant_invitations SET expires_at=now()-interval '1 minute' WHERE id='${expiredInvitation.id}'"`
    const { execFileSync } = await import('node:child_process')
    execFileSync('bash', ['-lc', expireCommand], { stdio: 'ignore' })
    await acceptInvitation(browser, users[3].username, users[3].password, expiredUrl, 410)
    record('Expired invitation rejected after isolated synthetic-row expiry')

    const revokedUrl = await invite(owner.page, users[4].email, 'VIEWER')
    const revokedItem = owner.page.locator('.compact-list > div').filter({ hasText: users[4].email })
    await revokedItem.getByRole('button', { name: 'Revoke' }).click()
    assert(await owner.page.locator('.compact-list > div').filter({ hasText: users[4].email }).count() === 0, 'revoked invitation disappears from pending list')
    await acceptInvitation(browser, users[4].username, users[4].password, revokedUrl, 410)
    record('Revoked invitation token rejected as single-use unavailable state (410)')

    await owner.page.getByRole('link', { name: 'Members & invites' }).click()
    const ownerRow = owner.page.locator('tr').filter({ hasText: users[0].email })
    assert(await ownerRow.count() === 1, 'owner membership is visible')
    await ownerRow.getByRole('combobox').selectOption('VIEWER')
    await owner.page.getByRole('alert').waitFor()
    assert((await ownerRow.getByRole('combobox').inputValue()) === 'OWNER', 'last OWNER demotion rejected')
    owner.page.once('dialog', dialog => dialog.accept())
    await owner.page.getByRole('button', { name: 'Leave organization' }).click()
    await owner.page.getByRole('alert').waitFor()
    assert((await ownerRow.getByRole('combobox').inputValue()) === 'OWNER', 'last OWNER leave rejected')
    record('Last active OWNER demotion and leave are rejected')

    // PUBLIC playback is checked in a fresh anonymous context through the real Gateway proxy.
    const publicContext = await browser.newContext()
    const publicPage = await publicContext.newPage()
    await publicPage.goto(`${APP}/watch/${broadcastA.publicId}`)
    await publicPage.waitForFunction(() => {
      const video = document.querySelector('video')
      return video && video.videoWidth > 0 && video.currentTime > 0.25
    }, { timeout: 20000 })
    record('Anonymous PUBLIC HLS playback decoded synthetic video through Gateway proxy')
    const embedPage = await browser.newPage()
    await embedPage.goto(`${APP}/`)
    await embedPage.evaluate((src) => { document.body.innerHTML = `<iframe id="broadcast-embed" src="${src}" title="acceptance" referrerpolicy="no-referrer"></iframe>` }, `${APP}/watch/${broadcastA.publicId}?embed=1`)
    const frame = embedPage.frameLocator('#broadcast-embed')
    await frame.locator('video').waitFor()
    await frame.locator('video').evaluate(video => video.play())
    await frame.locator('video').waitFor({ state: 'visible' })
    await embedPage.waitForTimeout(1200)
    assert(await frame.locator('video').evaluate(video => video.videoWidth > 0), 'iframe player decoded HLS video')
    record('PUBLIC iframe playback rendered and decoded synthetic HLS')
    await publicContext.close()
    await embedPage.close()

    // PRIVATE view requires the accepted viewer's OIDC identity and tenant membership.
    await setVisibility(owner.page, broadcastA, 'PRIVATE')
    const viewer = await login(browser, users[1].username, users[1].password)
    contexts.push(viewer.context)
    await viewer.page.waitForFunction(() => sessionStorage.getItem('streaming.selectedTenant') !== null)
    const privatePage = await viewer.context.newPage()
    await privatePage.goto(`${APP}/watch/${broadcastA.publicId}?tenant=${tenantA}`)
    await privatePage.waitForFunction(() => {
      const video = document.querySelector('video')
      return video && video.videoWidth > 0 && video.currentTime > 0.25
    }, { timeout: 20000 })
    record('Authorized PRIVATE OIDC member received a playback grant and decoded HLS')

    const cameraDenied = await apiRequest('GET', `/v1/cameras/${cameraB}`, viewerAccessToken, tenantB)
    const broadcastDenied = await apiRequest('GET', `/v1/broadcasts/${broadcastB.id}`, viewerAccessToken, tenantB)
    assert(cameraDenied.status === 404 && broadcastDenied.status === 404, `cross-tenant Camera/Broadcast access denied (${cameraDenied.status}/${broadcastDenied.status})`)
    const privateDenied = await apiRequest('GET', `/playback/${broadcastB.publicId}/token`, viewerAccessToken, tenantB)
    assert(privateDenied.status === 401, `cross-tenant PRIVATE playback denied (${privateDenied.status})`)
    record('Cross-tenant Camera, Broadcast and PRIVATE grant requests denied')

    // Anonymous PRIVATE playback is denied before membership revocation.
    const anonymousPrivate = await fetch(`${GATEWAY}/playback/${broadcastA.publicId}/token`)
    assert(anonymousPrivate.status === 401, `anonymous PRIVATE grant denied (${anonymousPrivate.status})`)
    record('Anonymous PRIVATE playback grant denied (401)')

    // Check the real iframe embed route for PRIVATE broadcasts while OIDC is active.
    const privateEmbed = await viewer.context.newPage()
    await privateEmbed.goto(`${APP}/`)
    await privateEmbed.evaluate((src) => { document.body.innerHTML = `<iframe id="broadcast-embed" src="${src}" title="acceptance" referrerpolicy="no-referrer"></iframe>` }, `${APP}/watch/${broadcastA.publicId}?tenant=${tenantA}&embed=1`)
    const privateFrame = privateEmbed.frameLocator('#broadcast-embed')
    await privateFrame.locator('video').waitFor()
    await privateEmbed.waitForTimeout(1800)
    assert(await privateFrame.locator('video').evaluate(video => video.videoWidth > 0), 'private iframe receives authorized OIDC playback')
    record('PRIVATE iframe playback succeeds for an authenticated member')

    // Membership removal is performed through the OWNER UI; subsequent grant renewal must fail.
    await owner.page.getByRole('link', { name: 'Members & invites' }).click()
    const viewerRow = owner.page.locator('tr').filter({ hasText: users[1].email })
    await viewerRow.getByRole('combobox').selectOption('OPERATOR')
    await viewerRow.getByRole('combobox').selectOption('VIEWER')
    owner.page.once('dialog', dialog => dialog.accept())
    await viewerRow.getByRole('button', { name: 'Remove' }).click()
    await owner.page.getByText(users[1].email).waitFor({ state: 'detached' })
    const revokedPlayback = await apiRequest('GET', `/playback/${broadcastA.publicId}/token`, viewerAccessToken, tenantA)
    assert(revokedPlayback.status === 401, `membership revocation blocks grant renewal (${revokedPlayback.status})`)
    await privatePage.reload()
    await privatePage.getByRole('status').filter({ hasText: 'private' }).waitFor({ timeout: 10000 })
    record('Member role edit/removal and immediate PRIVATE playback grant revocation')

    // The account remains authenticated after reload, then the OIDC logout returns to the public app.
    await owner.page.reload()
    await waitForWorkspace(owner.page)
    await owner.page.getByRole('button', { name: 'Sign out' }).click()
    await owner.page.waitForURL(url => url.origin === APP, { timeout: 20000 })
    assert(!owner.page.url().includes('/app'), 'logout leaves protected SaaS route')
    record('OIDC logout returns browser out of protected SaaS application')

    console.log(`SUMMARY ${passed.length} browser scenarios passed`)
  } finally {
    for (const context of contexts) await context.close().catch(() => {})
    await browser.close()
  }
}

main().catch(error => {
  const message = String(error?.message || 'Browser acceptance failed')
    .replace(/([?#])[^\s]*/g, '$1[redacted]')
    .replace(/Bearer\s+\S+/g, 'Bearer [redacted]')
  console.error(`FAIL ${message}`)
  process.exitCode = 1
})
