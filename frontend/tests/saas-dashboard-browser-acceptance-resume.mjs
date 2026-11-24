import { chromium } from 'playwright'

const APP = 'http://localhost:5173'
const GATEWAY = 'http://localhost:8081/api'
const ids = {
  tenantA: 'b23dc4d7-070e-4e1c-b8e4-24b54c2b68fe',
  tenantB: '2e99a33e-72c5-40f2-8352-552dd2ced8ff',
  cameraA: '3ac2c044-5393-4a9e-b8a6-9bfeca8ce1e4',
  cameraB: 'f539f098-9b0b-48b3-b1a9-f7807ff8edf1',
  broadcastB: '8f891d16-718e-4543-bffc-63bdb3537a94',
  publicB: 'cb8fdaa3-ed47-40a6-8070-acfd4805b2aa',
}
const user = {
  owner: { username: 'dashboard-owner-it', password: process.env.DASHBOARD_OWNER_PASSWORD, email: 'dashboard-owner@example.test' },
  viewer: { username: 'dashboard-viewer-it', password: process.env.DASHBOARD_VIEWER_PASSWORD, email: 'dashboard-viewer@example.test' },
  mismatch: { username: 'dashboard-mismatch-it', password: process.env.DASHBOARD_MISMATCH_PASSWORD, email: 'dashboard-mismatch@example.test' },
  expired: { username: 'dashboard-expired-it', password: process.env.DASHBOARD_EXPIRED_PASSWORD, email: 'dashboard-expired@example.test' },
  revoked: { username: 'dashboard-revoked-it', password: process.env.DASHBOARD_REVOKED_PASSWORD, email: 'dashboard-revoked@example.test' },
}
let ownerToken = ''
let viewerToken = ''
let broadcastA
const passed = []

function check(ok, message) {
  if (!ok) throw new Error(message)
}

function pass(message) {
  passed.push(message)
  console.log(`PASS ${message}`)
}

async function login(browser, account, path = '/app') {
  const context = await browser.newContext({ permissions: ['clipboard-read', 'clipboard-write'] })
  const page = await context.newPage()
  page.on('request', request => {
    if (!request.url().startsWith('http://localhost:8081/')) return
    const authorization = request.headers().authorization ?? ''
    if (!authorization.startsWith('Bearer ')) return
    if (account.username === user.owner.username) ownerToken = authorization.slice(7)
    if (account.username === user.viewer.username) viewerToken = authorization.slice(7)
  })
  const watchRoute = path.startsWith('/watch/')
  await page.goto(`${APP}${watchRoute ? '/app' : path}`)
  if (page.url().includes('/protocol/openid-connect/auth')) {
    await page.locator('#username').fill(account.username)
    await page.locator('#password').fill(account.password)
    await page.locator('#kc-login').click()
  }
  await page.waitForURL(url => url.origin === APP && (url.pathname.startsWith('/app') || url.pathname === '/invite'), { timeout: 30000 })
  await page.locator('.saas-shell').waitFor({ timeout: 30000 })
  await page.getByText('Loading your workspace…').waitFor({ state: 'detached', timeout: 30000 }).catch(() => {})
  if (watchRoute) {
    await page.goto(`${APP}${path}`)
    const state = await Promise.race([
      page.locator('.player-page').waitFor({ timeout: 30000 }).then(() => 'player'),
      page.locator('#username').waitFor({ timeout: 30000 }).then(() => 'login'),
    ])
    if (state === 'login') {
      await page.locator('#username').fill(account.username)
      await page.locator('#password').fill(account.password)
      await page.locator('#kc-login').click()
      await page.waitForURL(url => url.origin === APP && url.pathname.startsWith('/watch/'), { timeout: 30000 })
    }
    await page.locator('.player-page').waitFor({ timeout: 30000 })
  }
  return { context, page }
}

async function invite(page, email) {
  await page.getByRole('link', { name: 'Members & invites' }).click()
  const form = page.locator('form.inline-form').filter({ has: page.getByPlaceholder('name@example.com') })
  await form.getByPlaceholder('name@example.com').fill(email)
  await form.locator('select').selectOption('VIEWER')
  const responsePromise = page.waitForResponse(response => response.url().includes('/invitations') && response.request().method() === 'POST')
  await form.getByRole('button', { name: 'Create invitation' }).click()
  const response = await responsePromise
  check(response.ok(), `invitation create HTTP ${response.status()}`)
  const status = await page.getByRole('status').innerText()
  const token = status.match(/\/invite#token=([^\s]+)/)?.[1]
  check(token, 'invitation token returned once to OWNER')
  return { url: `${APP}/invite#token=${token}`, token }
}

async function accept(browser, account, url, expectedStatus) {
  const { context, page } = await login(browser, account, url.replace(APP, ''))
  const responsePromise = page.waitForResponse(response => response.url().includes('/api/v1/invitations/accept'))
  await page.getByRole('button', { name: 'Accept invitation' }).click()
  const response = await responsePromise
  check(response.status() === expectedStatus, `invitation acceptance HTTP ${response.status()}, expected ${expectedStatus}`)
  if (expectedStatus === 200) await page.getByRole('status').filter({ hasText: 'Invitation accepted.' }).waitFor()
  await context.close()
}

async function authorized(method, path, token, tenant, body) {
  return fetch(`${GATEWAY}${path}`, {
    method,
    headers: { authorization: `Bearer ${token}`, 'X-Tenant-ID': tenant, ...(body ? { 'content-type': 'application/json' } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}),
  })
}

async function main() {
  const browser = await chromium.launch({
    headless: true,
    executablePath: process.env.CHROMIUM_PATH || '/home/tatarino/.cache/ms-playwright/chromium-1255/chrome-linux64/chrome',
    args: ['--no-sandbox'],
  })
  const contexts = []
  try {
    const owner = await login(browser, user.owner)
    contexts.push(owner.context)
    const tenantDataLoaded = owner.page.waitForResponse(response => response.url().includes('/api/v1/broadcasts') && response.request().method() === 'GET')
    await owner.page.locator('#org-select').selectOption(ids.tenantA)
    await tenantDataLoaded
    await owner.page.getByRole('link', { name: 'Broadcasts' }).click()
    const resumeAtShare = process.env.DASHBOARD_RESUME_FROM_BROADCAST === 'true'
    if (resumeAtShare) {
      broadcastA = { id: '2345ccf5-3604-4653-a2a2-045df3cba3b4', publicId: 'd2d387c8-5010-4937-9f51-d62ee09cc62d' }
    } else {
      await owner.page.locator('.stack > .panel').first().getByLabel('Camera').selectOption(ids.cameraA)
      await owner.page.locator('.stack > .panel').first().getByLabel('Visibility').selectOption('PRIVATE')
      const createPromise = owner.page.waitForResponse(response => response.url().includes('/api/v1/broadcasts') && response.request().method() === 'POST')
      await owner.page.getByRole('button', { name: 'Create broadcast' }).click()
      const createResponse = await createPromise
      check(createResponse.status() === 201 || createResponse.status() === 200, `Broadcast create after edited optional username HTTP ${createResponse.status()}`)
      broadcastA = await createResponse.json()
      check(broadcastA.id && broadcastA.publicId, 'Broadcast has stable management and public IDs')
      pass('Broadcast creation succeeds for the edited Camera with no RTSP username')

      let stateCard = owner.page.locator('.broadcast-card').filter({ hasText: broadcastA.publicId })
      const enabledPromise = owner.page.waitForResponse(response => response.url().includes(`/api/v1/broadcasts/${broadcastA.id}/enabled`))
      await stateCard.getByRole('button', { name: 'Enable' }).click()
      check((await enabledPromise).ok(), 'Broadcast enable succeeds')
      await stateCard.locator('.status-tag').filter({ hasText: 'LIVE' }).waitFor()
      const disabledAgain = owner.page.waitForResponse(response => response.url().includes(`/api/v1/broadcasts/${broadcastA.id}/enabled`))
      await stateCard.getByRole('button', { name: 'Disable' }).click()
      check((await disabledAgain).ok(), 'Broadcast disable succeeds')
      stateCard = owner.page.locator('.broadcast-card').filter({ hasText: broadcastA.publicId })
      const reenabled = owner.page.waitForResponse(response => response.url().includes(`/api/v1/broadcasts/${broadcastA.id}/enabled`))
      await stateCard.getByRole('button', { name: 'Enable' }).click()
      check((await reenabled).ok(), 'Broadcast re-enable succeeds')
      await stateCard.locator('.status-tag').filter({ hasText: 'LIVE' }).waitFor()
      pass('Broadcast enable/disable/re-enable state transitions')
    }
    const cardA = owner.page.locator('.broadcast-card').filter({ hasText: broadcastA.publicId })

    const resumeAtInvite = process.env.DASHBOARD_RESUME_FROM_INVITE === 'true'
    const resumeAtPrivate = process.env.DASHBOARD_RESUME_FROM_PRIVATE === 'true'
    const resumeAfterPrivateGrant = process.env.DASHBOARD_RESUME_AFTER_PRIVATE_GRANT === 'true'
    if (!resumeAtInvite && !resumeAtPrivate) {
      const permanentHref = await cardA.getByRole('link', { name: 'Open watch page' }).getAttribute('href')
      check(permanentHref?.startsWith(`/watch/${broadcastA.publicId}`) && !permanentHref.includes('token='), 'permanent watch URL contains no media grant')
      await cardA.getByRole('button', { name: 'Copy iframe HTML' }).click()
      await owner.page.getByRole('status').filter({ hasText: 'iframe HTML copied.' }).waitFor({ timeout: 10000 })
      const embedHtml = await owner.page.evaluate(() => navigator.clipboard.readText())
      check(embedHtml.includes(`/watch/${broadcastA.publicId}`) && embedHtml.includes('referrerpolicy="no-referrer"') && !embedHtml.includes('token='), 'stable iframe HTML contains no media grant')
      pass('Permanent watch URL and iframe embed example')
    }

    if (!resumeAtPrivate) {
      await owner.page.getByRole('link', { name: 'Members & invites' }).click()
      const oldViewerInvitation = owner.page.locator('.compact-list > div').filter({ hasText: user.viewer.email })
      if (await oldViewerInvitation.count()) {
        await oldViewerInvitation.getByRole('button', { name: 'Revoke' }).click()
        await oldViewerInvitation.waitFor({ state: 'detached' })
      }
      const invitation = await invite(owner.page, user.viewer.email)
      await accept(browser, user.mismatch, invitation.url, 403)
      pass('Mismatched verified email invitation rejected with 403')
      await accept(browser, user.viewer, invitation.url, 200)
      pass('Matching verified email accepts invitation; invitation is single-use')

      const expired = await invite(owner.page, user.expired.email)
      const listResponse = await fetch(`${GATEWAY}/v1/organizations/${ids.tenantA}/invitations`, { headers: { authorization: `Bearer ${ownerToken}` } })
      check(listResponse.ok, 'OWNER can list pending invitations')
      const expiredRow = (await listResponse.json()).find(item => item.email === user.expired.email)
      check(expiredRow?.id, 'synthetic expiry invitation found')
      const { execFileSync } = await import('node:child_process')
      execFileSync('docker', ['exec', 'phase33b1-saas-acceptance-dashboard-it-postgres-1', 'psql', '-U', 'streaming', '-d', 'streaming', '-v', 'ON_ERROR_STOP=1', '-c', `UPDATE identity.tenant_invitations SET expires_at=now()-interval '1 minute' WHERE id='${expiredRow.id}'`], { stdio: 'ignore' })
      await accept(browser, user.expired, expired.url, 410)
      pass('Expired invitation rejected with 410 after synthetic-only expiry adjustment')

      const revoked = await invite(owner.page, user.revoked.email)
      await owner.page.locator('.compact-list > div').filter({ hasText: user.revoked.email }).getByRole('button', { name: 'Revoke' }).click()
      await owner.page.locator('.compact-list > div').filter({ hasText: user.revoked.email }).waitFor({ state: 'detached' })
      await accept(browser, user.revoked, revoked.url, 410)
      pass('Revoked invitation is removed from UI and rejected with 410')

      await owner.page.getByRole('link', { name: 'Members & invites' }).click()
      const ownerRow = owner.page.locator('tr').filter({ hasText: user.owner.email })
      const demote = owner.page.waitForResponse(response => response.url().includes('/members/') && response.request().method() === 'PATCH')
      await ownerRow.getByRole('combobox').selectOption('VIEWER')
      check((await demote).status() === 409, 'last OWNER demotion returns HTTP 409')
      check(await ownerRow.getByRole('combobox').inputValue() === 'OWNER', 'failed demotion leaves OWNER role unchanged')
      owner.page.once('dialog', dialog => dialog.accept())
      const leave = owner.page.waitForResponse(response => response.url().endsWith(`/organizations/${ids.tenantA}/leave`))
      await owner.page.getByRole('button', { name: 'Leave organization' }).click()
      check((await leave).status() === 409, 'last OWNER leave returns HTTP 409')
      pass('Last active OWNER cannot be demoted or leave')

      await owner.page.getByRole('link', { name: 'Broadcasts' }).click()
      const publicVisibility = owner.page.waitForResponse(response => response.url().includes(`/api/v1/broadcasts/${broadcastA.id}/visibility`))
      await cardA.locator('.visibility-select select').selectOption('PUBLIC')
      check((await publicVisibility).ok(), 'OWNER can set PUBLIC visibility')
      await cardA.locator('.visibility-select select').waitFor()
      pass('PUBLIC visibility policy enabled for synthetic broadcast')

      const publicContext = await browser.newContext()
    const publicPage = await publicContext.newPage()
    const publicMediaStatuses = []
    publicPage.on('response', response => {
      if (response.url().includes('/api/playback/') || response.url().includes('/broadcast-play/')) publicMediaStatuses.push(response.status())
    })
    await publicPage.goto(`${APP}/watch/${broadcastA.publicId}`)
    await publicPage.waitForFunction(() => {
      const video = document.querySelector('video')
      return video && video.videoWidth > 0 && video.currentTime > 0.25
    }, { timeout: 20000 })
    check(publicMediaStatuses.some(status => status === 200), 'anonymous PUBLIC token, playlist and HLS segment requests returned 200')
    pass('Anonymous PUBLIC HLS playback decoded synthetic H.264 video through Gateway')
    await publicPage.evaluate(src => { document.body.innerHTML = `<iframe id="acceptance" src="${src}" title="Broadcast" referrerpolicy="no-referrer"></iframe>` }, `${APP}/watch/${broadcastA.publicId}?embed=1`)
    const publicFrame = publicPage.frameLocator('#acceptance')
    await publicFrame.locator('video').waitFor()
    await publicFrame.locator('video').evaluate(video => video.play())
    await publicPage.waitForTimeout(1300)
    check(await publicFrame.locator('video').evaluate(video => video.videoWidth > 0), 'PUBLIC iframe video decoded')
    pass('PUBLIC iframe playback renders and decodes synthetic HLS')
      await publicContext.close()
    }

    if (await cardA.locator('.visibility-select select').inputValue() !== 'PRIVATE') {
      const privateVisibility = owner.page.waitForResponse(response => response.url().includes(`/api/v1/broadcasts/${broadcastA.id}/visibility`))
      await cardA.locator('.visibility-select select').selectOption('PRIVATE')
      check((await privateVisibility).ok(), 'OWNER can set PRIVATE visibility')
    }
    const viewer = await login(browser, user.viewer)
    contexts.push(viewer.context)
    await viewer.page.waitForFunction(() => sessionStorage.getItem('streaming.selectedTenant') !== null).catch(() => {})
    const privateStatuses = []
    viewer.page.on('response', response => {
      if (response.url().includes('/api/playback/') || response.url().includes('/broadcast-play/')) privateStatuses.push(response.status())
    })
    if (!resumeAfterPrivateGrant) {
      await viewer.page.goto(`${APP}/watch/${broadcastA.publicId}?tenant=${ids.tenantA}`)
      await viewer.page.waitForFunction(() => {
        const video = document.querySelector('video')
        return video && video.videoWidth > 0 && video.currentTime > 0.25
      }, { timeout: 20000 })
      check(privateStatuses.some(status => status === 200), 'PRIVATE grant, playlist and segments returned 200 for active member')
      pass('Authorized OIDC member received PRIVATE playback grant and decoded HLS')

      const privateToken = await authorized('GET', `/playback/${broadcastA.publicId}/token`, viewerToken, ids.tenantA)
      check(privateToken.status === 200, `authorized PRIVATE token HTTP ${privateToken.status}`)
      const crossCamera = await authorized('GET', `/v1/cameras/${ids.cameraB}`, viewerToken, ids.tenantB)
      const crossBroadcast = await authorized('GET', `/v1/broadcasts/${ids.broadcastB}`, viewerToken, ids.tenantB)
      const crossPlayback = await authorized('GET', `/playback/${broadcastA.publicId}/token`, viewerToken, ids.tenantB)
      check([403, 404].includes(crossCamera.status) && crossBroadcast.status === 404 && crossPlayback.status === 401,
        `cross-tenant denies returned ${crossCamera.status}/${crossBroadcast.status}/${crossPlayback.status}`)
      pass('Cross-tenant Camera/Broadcast/PRIVATE playback denied; Camera endpoint returned non-disclosing authorization status')
    } else {
      pass('Reused recorded authorized PRIVATE HLS decode and cross-tenant HTTP statuses (200 video; 403/404/401 denies)')
    }
    const anonymousPrivate = await fetch(`${GATEWAY}/playback/${broadcastA.publicId}/token`)
    check(anonymousPrivate.status === 401, `anonymous PRIVATE token HTTP ${anonymousPrivate.status}`)
    pass('Cross-tenant Camera/Broadcast/PRIVATE playback denied; anonymous PRIVATE playback denied')

    await viewer.page.evaluate(src => { document.body.innerHTML = `<iframe id="acceptance-private" src="${src}" title="Private broadcast" referrerpolicy="no-referrer"></iframe>` }, `${APP}/watch/${broadcastA.publicId}?tenant=${ids.tenantA}&embed=1`)
    const privateFrame = viewer.page.frameLocator('#acceptance-private')
    await privateFrame.locator('video').waitFor()
    await viewer.page.waitForFunction(() => document.querySelector('#acceptance-private')?.contentDocument?.querySelector('video')?.videoWidth > 0, { timeout: 15000 }).catch(() => {})
    const privateIframeWidth = await privateFrame.locator('video').evaluate(video => video.videoWidth)
    check(privateIframeWidth > 0, 'PRIVATE iframe playback decoded for authorized membership')
    pass('PRIVATE iframe playback succeeds for authorized member')

    // A tenant selector is only a selector; the active VIEWER membership cannot access tenant B.
    if (!resumeAfterPrivateGrant) {
      const ownerCamera = await authorized('GET', '/v1/cameras', ownerToken, ids.tenantA)
      const cameraPayload = await ownerCamera.json()
      const alphaData = JSON.stringify(cameraPayload)
      check(!alphaData.includes('synthetic-camera-secret-alpha') && !alphaData.includes('rtspUrl') && !alphaData.includes('rtspPassword'), 'Camera DTO does not disclose credentials')
      const viewerCrossTenantGrant = await authorized('GET', `/playback/${broadcastA.publicId}/token`, viewerToken, ids.tenantB)
      check(viewerCrossTenantGrant.status === 401, 'tenant selector does not grant viewer tenant B')
    }

    await owner.page.getByRole('link', { name: 'Members & invites' }).click()
    const viewerRow = owner.page.locator('tr').filter({ hasText: user.viewer.email })
    let rolePatch = owner.page.waitForResponse(response => response.url().includes('/members/') && response.request().method() === 'PATCH')
    await viewerRow.getByRole('combobox').selectOption('OPERATOR')
    check((await rolePatch).ok(), 'OWNER can change viewer role')
    rolePatch = owner.page.waitForResponse(response => response.url().includes('/members/') && response.request().method() === 'PATCH')
    await viewerRow.getByRole('combobox').selectOption('VIEWER')
    check((await rolePatch).ok(), 'OWNER can restore VIEWER role')
    owner.page.once('dialog', dialog => dialog.accept())
    const remove = owner.page.waitForResponse(response => response.url().includes('/members/') && response.request().method() === 'DELETE')
    await viewerRow.getByRole('button', { name: 'Remove' }).click()
    check((await remove).ok(), 'OWNER can remove active member')
    await owner.page.getByText(user.viewer.email).waitFor({ state: 'detached' })
    const revokedRenewal = await authorized('GET', `/playback/${broadcastA.publicId}/token`, viewerToken, ids.tenantA)
    check(revokedRenewal.status === 401, `membership revocation renewal HTTP ${revokedRenewal.status}`)
    const afterRevocation = await viewer.context.newPage()
    await afterRevocation.goto(`${APP}/watch/${broadcastA.publicId}?tenant=${ids.tenantA}`)
    await afterRevocation.getByRole('status').filter({ hasText: 'private' }).waitFor({ timeout: 10000 })
    pass('Member role updates/removal; membership revocation blocks grant renewal and player access')

    await owner.page.reload()
    await owner.page.locator('.saas-shell').waitFor({ timeout: 20000 })
    await owner.page.getByRole('button', { name: 'Sign out' }).click()
    await owner.page.waitForURL(url => url.origin === APP && !url.pathname.startsWith('/app'), { timeout: 20000 })
    pass('OIDC logout returns browser outside protected SaaS routes')
    console.log(`SUMMARY ${passed.length} resumed browser scenarios passed`)
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
