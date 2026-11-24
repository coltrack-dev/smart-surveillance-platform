import { chromium } from 'playwright'

const APP = 'http://localhost:5173'
const ids = {
  tenantA: 'b23dc4d7-070e-4e1c-b8e4-24b54c2b68fe',
  broadcastA: '2345ccf5-3604-4653-a2a2-045df3cba3b4',
  publicA: 'd2d387c8-5010-4937-9f51-d62ee09cc62d',
}
const owner = {
  username: 'dashboard-owner-it',
  password: process.env.DASHBOARD_OWNER_PASSWORD,
}

function check(condition, message) {
  if (!condition) throw new Error(message)
  console.log(`PASS ${message}`)
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
  await page.locator('#org-select').selectOption(ids.tenantA)
}

async function main() {
  const browser = await chromium.launch({
    headless: true,
    executablePath: process.env.CHROMIUM_PATH || '/home/tatarino/.cache/ms-playwright/chromium-1255/chrome-linux64/chrome',
    args: ['--no-sandbox'],
  })
  const context = await browser.newContext({ permissions: ['clipboard-read', 'clipboard-write'] })
  const page = await context.newPage()
  try {
    await login(page)

    const layoutOnly = process.env.DASHBOARD_LAYOUT_ONLY === 'true'
    if (!layoutOnly) {
      await page.getByRole('link', { name: 'Broadcasts' }).click()
      const broadcast = page.locator('.broadcast-card').filter({ hasText: ids.publicA })
      await broadcast.getByRole('button', { name: 'Copy watch URL' }).click()
      await page.getByRole('status').filter({ hasText: 'Permanent watch URL copied.' }).waitFor()
      const watchUrl = await page.evaluate(() => navigator.clipboard.readText())
      check(watchUrl.length > 0, 'browser clipboard returned permanent watch URL')
      const parsedWatch = new URL(watchUrl, APP)
      check(parsedWatch.pathname === `/watch/${ids.publicA}` && parsedWatch.searchParams.get('tenant') === ids.tenantA,
        'permanent PRIVATE watch URL copied with tenant selector and no media grant')
      check(!/[?&](token|grant)=/.test(watchUrl) && !/Bearer\s|rtsp/i.test(watchUrl),
        'permanent watch clipboard value contains no JWT, playback grant or RTSP credentials')

      await page.getByRole('link', { name: 'Members & invites' }).click()
      const email = `dashboard-clipboard-${Date.now()}@example.test`
      const form = page.locator('form.inline-form').filter({ has: page.getByPlaceholder('name@example.com') })
      await form.getByPlaceholder('name@example.com').fill(email)
      await form.locator('select').selectOption('VIEWER')
      const inviteResponse = page.waitForResponse(response => response.url().includes('/invitations') && response.request().method() === 'POST')
      await form.getByRole('button', { name: 'Create invitation' }).click()
      check((await inviteResponse).ok(), 'synthetic OWNER invitation created')
      await page.getByRole('button', { name: 'Copy invitation link' }).waitFor()
      await page.getByRole('button', { name: 'Copy invitation link' }).click()
      await page.getByRole('status').filter({ hasText: 'Invitation link copied.' }).waitFor()
      const invitationUrl = await page.evaluate(() => navigator.clipboard.readText())
      check(invitationUrl.length > 0, 'browser clipboard returned invitation link')
      const parsedInvite = new URL(invitationUrl, APP)
      check(parsedInvite.origin === new URL(APP).origin && parsedInvite.pathname === '/invite' && Boolean(parsedInvite.hash.match(/^#token=.+/)),
        'one-time invitation link copied to browser clipboard')
      check(!/Bearer\s|broadcast-play|rtsp/i.test(invitationUrl) && !/[?&](access_token|playback_grant)=/.test(invitationUrl),
        'invitation clipboard value contains no JWT, media grant or internal credentials')
      const invitationRow = page.locator('.compact-list > div').filter({ hasText: email })
      await invitationRow.getByRole('button', { name: 'Revoke' }).click()
      await invitationRow.waitFor({ state: 'detached' })
    }

    const viewports = [
      { name: 'desktop', width: 1440, height: 900 },
      { name: 'tablet', width: 768, height: 1024 },
      { name: 'mobile', width: 390, height: 844 },
    ].filter(viewport => !process.env.DASHBOARD_VIEWPORT || process.env.DASHBOARD_VIEWPORT.split(',').includes(viewport.name))
    for (const viewport of viewports) {
      await page.setViewportSize({ width: viewport.width, height: viewport.height })
      const sections = [
        ['overview', 'Overview', '.content-grid'],
        ['cameras', 'Cameras', '.table-wrap'],
        ['broadcasts', 'Broadcasts', '.broadcast-card'],
        ['members', 'Members & invites', '.table-wrap'],
      ].filter(([section]) => !process.env.DASHBOARD_SECTION_ONLY || process.env.DASHBOARD_SECTION_ONLY.split(',').includes(section))
      for (const [section, label, selector] of sections) {
        await page.getByRole('link', { name: label }).click()
        await page.locator(selector).first().waitFor({ timeout: 10000 })
        const layout = await page.evaluate(() => {
          const selector = document.querySelector('#org-select')
          const rect = selector?.getBoundingClientRect()
          return {
            width: window.innerWidth,
            scrollWidth: document.documentElement.scrollWidth,
            shell: Boolean(document.querySelector('.saas-shell')),
            orgSelectorVisible: Boolean(rect && rect.width > 0 && rect.left >= 0 && rect.right <= window.innerWidth),
            overflows: [...document.querySelectorAll('body *')].map(element => {
              const box = element.getBoundingClientRect()
              return { tag: element.tagName, id: element.id, className: typeof element.className === 'string' ? element.className : '', left: Math.round(box.left), right: Math.round(box.right), width: Math.round(box.width), scrollWidth: element.scrollWidth }
            }).filter(box => box.width > 0 && (box.left < -1 || box.right > window.innerWidth + 1)).slice(0, 12),
            cameraPanelAncestors: [...(document.querySelector('.stack > .panel')?.parentElement?.parentElement?.parentElement ? [document.querySelector('.stack > .panel')]:[])].flatMap(panel => {
              const result = []
              let element = panel
              while (element && result.length < 7) {
                const box = element.getBoundingClientRect()
                const style = getComputedStyle(element)
                result.push({ tag: element.tagName, className: typeof element.className === 'string' ? element.className : '', left: Math.round(box.left), right: Math.round(box.right), width: Math.round(box.width), minWidth: style.minWidth, gridColumns: style.gridTemplateColumns })
                element = element.parentElement
              }
              return result
            })[0] ?? [],
          }
        })
        check(layout.shell && layout.scrollWidth <= layout.width && layout.orgSelectorVisible,
          `${viewport.name} ${section} navigation and organization switcher fit without page overflow${layoutOnly ? ` ${JSON.stringify(layout)}` : ''}`)
      }
      await page.goto(`${APP}/watch/${ids.publicA}`)
      await page.locator('.player-page').waitFor()
      const player = await page.locator('video').boundingBox()
      const pageWidth = await page.evaluate(() => ({ width: innerWidth, scrollWidth: document.documentElement.scrollWidth }))
      check(Boolean(player && player.width > 0 && player.x >= 0 && player.x + player.width <= viewport.width + 1 && pageWidth.scrollWidth <= pageWidth.width),
        `${viewport.name} playback page fits viewport without horizontal page overflow`)
      await page.goto(`${APP}/app`)
      await page.locator('.saas-shell').waitFor()
    }

    console.log(layoutOnly
      ? 'SUMMARY selected responsive viewport checks completed; clipboard checks were skipped'
      : 'SUMMARY clipboard and responsive acceptance completed; iframe clipboard PASS is reused from prior browser evidence')
  } finally {
    await context.close()
    await browser.close()
  }
}

main().catch(error => {
  const message = String(error?.message || 'Dashboard acceptance failed')
    .replace(/([?#])[^\s]*/g, '$1[redacted]')
    .replace(/Bearer\s+\S+/g, 'Bearer [redacted]')
  console.error(`FAIL ${message}`)
  process.exitCode = 1
})
