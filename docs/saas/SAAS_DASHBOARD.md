# Streaming SaaS Dashboard — Phase 3.3B

## Scope and routes

The dashboard is part of the existing Vue 3 SPA (`frontend/`), not a second frontend. It is enabled only when built with `VITE_AUTH_MODE=oidc`, as in `docker-compose.streaming.yml`. In Streaming, `/` and the legacy `/analytics` route redirect to `/app` after authentication. The Surveillance build retains its `/`, `/analytics` and anonymous `/watch/{publicId}` behavior; SaaS-only routes redirect to `/` when OIDC mode is not enabled.

| Route | Purpose | Authentication |
|---|---|---|
| `/app` or `/app/overview` | Organization overview and counts | Keycloak OIDC, Authorization Code + PKCE |
| `/app/cameras` | Tenant camera list and registration/editing | OIDC + selected tenant header; server checks membership/role |
| `/app/broadcasts` | Broadcast creation, visibility, enable/disable and share links | OIDC + selected tenant header; server checks permission |
| `/app/members` | Members, roles and invitations | OIDC; Identity Service enforces roles |
| `/invite#token=…` | Accept one invitation after login | OIDC and verified matching email required by Identity Service |
| `/watch/{publicId}` | Public player / iframe | Anonymous for enabled PUBLIC broadcasts; PRIVATE requires a signed-in member |

The app initializes the existing `keycloak-js` PKCE client through the router guard. Keycloak handles the configured root callback during `init`; a temporary session-scoped return path restores the requested SaaS screen after login. Invite fragment tokens are moved into session-scoped state and stripped from the address bar before OIDC navigation. Sign-out uses the provider session. Access tokens remain in Keycloak's in-memory client state and are not written to localStorage. `/api/v1/identity/me` supplies the displayed profile; `/api/v1/identity/me/memberships` and `/api/v1/organizations` supply organization choices.

## Component and API structure

- `src/views/StreamingApp.vue` contains the SaaS shell, sidebar, organization switcher, overview, cameras, broadcasts, members/invitations and invitation acceptance views.
- `src/api/saas.ts` contains typed Identity, Camera and Broadcast API calls.
- `src/api/http.ts` centralizes bearer attachment, selected `X-Tenant-ID` on Camera/Stream/Broadcast management paths, and session/access-change events.
- `src/auth/oidc.ts` owns Keycloak initialization, token refresh, display metadata and logout.
- `src/views/BroadcastPlayer.vue` remains the standalone HLS player. It first requests anonymous playback authorization. If a PRIVATE broadcast denies that request, it retries with an in-memory OIDC token and tenant selector; the server still checks membership. HLS media requests use only short-lived playback grants.
- `src/router/index.ts` retains anonymous `/watch/:publicId` and adds protected SaaS routes.

The selected organization is stored in `sessionStorage` as UI state. It is attached only as `X-Tenant-ID`; backend membership and permission checks remain authoritative. Switching organization clears tenant-scoped collections before loading the new selection. A 401 triggers session handling; 403/404 triggers an access refresh. Error messages do not display server response bodies or credentials.

Integrated Gateway endpoints:

- Identity: `GET /api/v1/identity/me`, `GET /api/v1/identity/me/memberships`.
- Organizations: `GET/POST /api/v1/organizations`, `GET/PATCH /api/v1/organizations/{tenantId}`.
- Members: `GET /api/v1/organizations/{tenantId}/members`, `PATCH/DELETE /api/v1/organizations/{tenantId}/members/{userId}`, `POST /api/v1/organizations/{tenantId}/leave`.
- Invitations: `POST/GET /api/v1/organizations/{tenantId}/invitations`, `DELETE /api/v1/organizations/{tenantId}/invitations/{invitationId}`, `POST /api/v1/invitations/accept`.
- Cameras: `GET/POST /api/v1/cameras`, `PUT /api/v1/cameras/{id}`.
- Broadcasts: `GET/POST /api/v1/broadcasts`, `PUT /api/v1/broadcasts/{id}/enabled`, `PUT /api/v1/broadcasts/{id}/visibility`, `DELETE /api/v1/broadcasts/{id}`, `GET /api/v1/broadcasts/{id}/playback`.
- Player: `GET /api/playback/{publicId}/token`, followed by protected Broadcast HLS proxy requests.

## Role visibility

| UI action | OWNER | ADMIN | OPERATOR | VIEWER |
|---|---:|---:|---:|---:|
| Read cameras/broadcasts | Yes | Yes | Yes | Yes |
| Create/edit camera, create/operate broadcast | Yes | Yes | Yes | No |
| Rename organization | Yes | No | No | No |
| Invite/remove OPERATOR or VIEWER | Yes | Yes | No | No |
| Manage OWNER/ADMIN roles/invitations | Yes | No | No | No |
| PRIVATE broadcast playback | Active membership + playback permission | Same | Same | Same |

This is usability filtering only. Backend authorization remains authoritative. Last active OWNER protection is enforced by Identity Service/database.

## Configuration and development

Streaming Compose builds the same frontend with `VITE_AUTH_MODE=oidc`, `VITE_API_URL` (default `http://localhost:8081/api`), required `VITE_OIDC_ISSUER_URI`, `VITE_OIDC_CLIENT_ID=streaming-vue`, `VITE_OIDC_REALM=streaming` and `VITE_OIDC_REDIRECT_URI` (default `http://localhost:5173/`). Configure the values with matching Keycloak callback/origin and a browser-reachable Gateway/issuer URL. Do not add a client secret. A production/runtime config change requires rebuilding the static SPA image. For local Vite development, export the same values and allow the Vite origin/callback in the development realm. See [deployment modes](../deployment-modes.md) for the isolated Streaming Compose setup.

Development invitation URL retrieval remains disabled unless the Identity Service's local-only setting is enabled. The UI shows a returned one-time invitation URL only at creation time; it does not persist or log the token. No email provider is configured.

## Known limitations

- No component-test framework existed; no new framework was added. `npm run build` runs Vue TypeScript checking and the Vite production bundle.
- Camera edit requires the operator to re-enter the RTSP URL because the API correctly never returns stored connection credentials. Validate the API's update semantics against an isolated live Streaming instance before onboarding sources.
- Browser acceptance results and remaining checks are recorded in the Phase 3.3B.1 section below. Session-expiration UI, explicit viewport/responsive acceptance, and the behavior of a PRIVATE watch URL opened before any OIDC sign-in remain unverified.
- PRIVATE permanent links include the tenant UUID only as a selector, never as proof or a credential. Keep `Referrer-Policy: no-referrer`, redact media token query parameters at ingress, and keep Streaming private until browser checks pass.
- No dashboard pagination/search, camera connection test/control UI, profile editor, audit log or email delivery.
- Usage metering, quotas, billing and tenant suspension are not included; plan them in Phase 3.3C/production work.

## Phase 3.3B.1 browser acceptance — initial blocked preflight (2026-11-24)

This is the historical preflight record. It was superseded by the isolated live acceptance recorded below; its blocked outcomes were not treated as test failures or as final status.

**Overall: BLOCKED — no live OIDC/dashboard acceptance is claimed.** Preflight found 19 GiB free disk and 23 GiB available RAM. The PostgreSQL client is 16.13, but `pg_isready -h 127.0.0.1 -p 5432` reported no response. Docker CLI access was checked (including an approved read-only daemon inspection), but the Docker daemon is not running; container, image-cache and volume inventories therefore could not be read. Playwright Chromium is installed at `/home/tatarino/.cache/ms-playwright/chromium-1255/chrome-linux64/chrome` and launched successfully for the legacy frontend smoke check below. No daemon was started because doing so could activate or alter existing containers before their state and volumes can be inspected.

Static Compose validation passed for `docker-compose.demo.yml`. Streaming `docker compose config -q` passed with dummy validation-only values for the required secret/issuer variables; no services were started. This validates Compose syntax only, not isolation or database volume compatibility.

| Acceptance scenario | Status | Evidence / limitation |
|---|---|---|
| Docker resource inventory and PostgreSQL volume compatibility | **BLOCKED** | Docker daemon unavailable; could not inspect existing containers/images/volumes or prove there is no PG17 data directory to avoid. No volume was mounted or changed. |
| PostgreSQL 16.13 isolated test server | **BLOCKED** | `psql --version` reports client 16.13; local server readiness check at 127.0.0.1:5432 returned no response. No database was contacted or migrated. |
| Streaming/Demo Compose static configuration | **PASSED (static only)** | Demo config passed; Streaming config passed with dummy required env values. No containers were started. |
| Chromium availability | **PASSED** | Playwright Chromium launched headless; no browser installation was needed. |
| Authorization Code + PKCE login, callback, `/app` return and reload persistence | **BLOCKED** | Requires live Keycloak, Gateway and browser. |
| Logout, session expiry and anonymous route protection | **BLOCKED** | Requires live OIDC session and browser. |
| Organization create, OWNER assignment, switch, rename and multi-tenant state clearing | **BLOCKED** | Requires isolated Identity Service/PostgreSQL and authenticated users. |
| Invitations, verified-email accept/mismatch, role changes/removal, last OWNER, revoke/expiry | **BLOCKED** | Requires two synthetic OIDC identities and isolated Identity database. No real identities or tenants were touched. |
| Camera create/list/edit and RTSP credential non-disclosure | **BLOCKED** | Requires isolated Camera/Gateway APIs. No synthetic camera records were created. |
| Broadcast create, visibility, enable/disable, permanent URL and iframe generation | **BLOCKED** | Requires isolated Stream/Camera APIs. No broadcasts were created. |
| PRIVATE anonymous deny / authorized grant / cross-tenant deny / revocation renewal | **BLOCKED** | Requires Gateway, Identity and Stream services plus synthetic membership. No HLS source or playback request was made. |
| PUBLIC anonymous playback through new dashboard/player integration | **BLOCKED** | The historical public-player browser E2E is not evidence for this Phase 3.3B.1 integration and was not repeated. |
| Surveillance frontend route smoke | **PASSED (local legacy build only)** | Built with `VITE_AUTH_MODE=legacy`, served on loopback and checked in Chromium: `/app` redirected to `/`, `.saas-shell` count was 0, `/watch/{syntheticId}` stayed on the watch route and rendered one `<video>` element. API calls were intercepted/aborted; no backend or playback was tested. |
| Full Surveillance deployment compatibility | **BLOCKED (runtime)** | Demo Compose syntax passed, but Docker daemon was unavailable, so the deployment/frontend image was not run. No Surveillance resources were touched. |

No screenshot was saved during that preflight.

## Phase 3.3B.1 browser acceptance — isolated live run (2026-11-24)

**Status: PARTIAL PASS — Phase 3.3B.1 remains open because required browser cases are still NOT RUN; public deployment remains disabled.** The isolated test environment was restored after Docker metadata recovery and used the dedicated Compose project `phase33b1-saas-acceptance`. Only its four containers were running: Streaming PostgreSQL, Keycloak PostgreSQL, Keycloak 26.8.2 and Kafka 3.9.1. The Streaming database container reported PostgreSQL 16.13. Its fresh project volumes were `phase33b1-saas-acceptance_dashboard-it-streaming-data`, `phase33b1-saas-acceptance_dashboard-it-keycloak-data` and `phase33b1-saas-acceptance_dashboard-it-kafka-data`. No Surveillance, `broadcast-it` or `phase32d` volume was mounted or modified.

The application JARs and Vite ran as host processes bound to loopback only: Identity 127.0.0.1:8099, Camera 127.0.0.1:8091, Stream 127.0.0.1:8094, Gateway 127.0.0.1:8081, Vite 127.0.0.1:5173 and synthetic HTTP-Basic HLS 127.0.0.1:8888. Keycloak and the test databases were exposed only on 127.0.0.1:18180 and 127.0.0.1:15432; Kafka only on 127.0.0.1:19092. The synthetic HLS source contained a short H.264 playlist/segment. Test credentials were synthetic and stored in a mode-0600 file under `/tmp`; no credentials are included here.

The OIDC test initially exposed a player return-path defect. An anonymous PRIVATE grant returned 401; `keycloak-js` then completed `check-sso` at `/`, and the Surveillance/Streaming router sent the browser to `/app`, losing `/watch/{publicId}`. `frontend/src/auth/oidc.ts` now uses the current watch URL as the OIDC initialization callback only on `/watch/**`; the Gateway remains the authorization authority. After the change, a logged-in tenant viewer retained the watch route, received a private grant and decoded video. The normal `/app` callback behavior remains unchanged.

| Scenario | Status | Observed evidence |
|---|---|---|
| Authorization Code + PKCE, callback to `/app`, protected SaaS shell | **PASS** | Chromium logged in synthetic OWNER/VIEWER identities through Keycloak; callback returned to `/app`. |
| Session persistence across reload; no access token in localStorage | **PASS** | Reload remained authenticated; browser storage check found no access token in localStorage. |
| Anonymous SaaS route protection | **PASS** | Anonymous `/app` navigation redirected to Keycloak before login. |
| Logout | **PASS** | OWNER sign-out completed at Keycloak and returned outside `/app`. |
| Session expiration UI | **NOT RUN** | No controlled expiry/revocation of the browser OIDC session was performed. |
| Create organization and automatic OWNER; rename; second organization and switch | **PASS** | Synthetic OWNER created/renamed two organizations; switching cleared the previous tenant camera/broadcast list. The same OWNER belonged to both organizations. |
| Invitation creation and verified email match/mismatch | **PASS** | OWNER created the one-time invitation; wrong verified email returned 403, invited verified email accepted with 200. The acceptance script read the one-time URL from the creation response/status. |
| Invitation expiry and revocation; single-use acceptance | **PASS** | Expired acceptance returned 410; revoked invitation acceptance returned 410; successful acceptance was single-use. Expiry timestamp was changed only for a synthetic invitation in the isolated database. |
| Role update, member removal and last OWNER protection | **PASS** | OWNER changed a synthetic VIEWER to OPERATOR and back, then removed the member. Last OWNER demotion and leave each returned 409. |
| Camera create/edit and credential secrecy | **PASS** | Synthetic RTSP Camera created and edited. Camera responses/UI did not contain RTSP URL/password fields or the synthetic password. A missing optional RTSP username uncovered and led to a null-safe `RtspUrlResolver` fix; its exact focused JUnit test passed (see Test Inventory). |
| Broadcast create, enable/disable, visibility and permanent links | **PASS** | Synthetic Broadcast created and toggled; PUBLIC/PRIVATE visibility changed. Stable watch URL was checked for absence of a media grant; iframe HTML was copied and checked for no grant plus `referrerpolicy="no-referrer"`. Clipboard copy of the plain watch URL was not explicitly exercised. |
| Anonymous PUBLIC HLS and iframe | **PASS** | Anonymous viewer obtained playback authorization and Chromium decoded the synthetic H.264 HLS video; PUBLIC iframe also decoded video. |
| Anonymous PRIVATE playback | **PASS** | Playback token request without authorization returned 401. |
| Authorized PRIVATE playback | **PASS** | After OIDC login as an active tenant member, protected token/playlist/segment requests succeeded and Chromium decoded H.264 video. |
| PRIVATE iframe | **PASS** | Authorized member's iframe rendered and decoded the synthetic HLS video. |
| Cross-tenant access | **PASS (denied)** | Synthetic viewer selecting Tenant B received Camera 403, Broadcast 404 and Tenant A PRIVATE playback grant 401. All requests were denied. Camera's 403 is the service's permission-denied response; Broadcast uses inaccessible-resource 404. Detailed response-body equivalence against a random unknown ID was not asserted in this browser run. |
| Membership revocation during PRIVATE playback | **PASS** | Removing the viewer caused subsequent playback-grant renewal to return 401; a new player request displayed the private/unavailable state. The already downloaded segment was not retroactively revoked. |
| Responsive viewport acceptance | **NOT RUN** | No viewport matrix or mobile-device emulation was executed. |
| PRIVATE watch opened before any OIDC sign-in | **NOT RUN** | The accepted path first signs in through `/app`, then opens the permanent watch URL. A fresh anonymous browser gets a denied/private state; an in-player sign-in prompt was not verified. |
| Invitation URL clipboard copy | **NOT RUN** | The browser test consumed the one-time link returned to the OWNER UI but did not exercise its clipboard button. |
| Full Surveillance runtime compatibility | **NOT RUN** | Existing Surveillance containers remained stopped/untouched; only the legacy build's historical static/route smoke remains evidence. |
| Session-token expiry and uninterrupted HLS renewal | **NOT RUN** | This run did not wait for an authorization grant to expire or verify automatic long-running renewal. |

The initial private scenario run failed only because the script required Camera 404 while the API correctly returned 403; its already observed requests were Camera 403, Broadcast 404 and playback 401. The script expectation was corrected to accept either 403 or 404 for Camera denial. The previously successful private HLS decode was not repeated after that correction; the remaining private iframe/revocation/logout checks then passed in the continuation. No live HLS/MediaMTX or full RTSP/FFmpeg suite was repeated.

**Commands/evidence:** the isolated Compose file is `docker-compose.dashboard-it.yml`, project `phase33b1-saas-acceptance`; static config was used to prepare the environment, not as a substitute for live checks. Browser continuation was run from `frontend/` with `DASHBOARD_RESUME_FROM_BROADCAST=true DASHBOARD_RESUME_FROM_PRIVATE=true DASHBOARD_RESUME_AFTER_PRIVATE_GRANT=true node tests/saas-dashboard-browser-acceptance-resume.mjs`, sourcing synthetic variables from `/tmp/phase33b1.env`. The second continuation reported five passes: reused private playback/cross-tenant evidence, anonymous PRIVATE denial, PRIVATE iframe decode, membership role/removal/revocation and logout. Earlier resumed runs recorded organization/invitation/camera/Broadcast/PUBLIC playback results above. `npm run build` passed after the callback fix (Vue typecheck + Vite); Vite warned that the main minified chunk is about 630 kB. No screenshots were saved.

**Result at the Phase 3.3B.1 checkpoint:** still open at that time. The remaining browser checks and later closure decision are recorded below.

## Phase 3.3B.2 final browser acceptance (2026-11-24)

**Status: PASS — Phase 3.3B is closed for the defined MVP acceptance scope.** Public ingress remains disabled. The acceptance reused project `phase33b1-saas-acceptance`, PostgreSQL 16.13 and Keycloak 26.8.2; no Docker container, PostgreSQL volume or Surveillance resource was started, stopped, rebuilt or modified in this phase. Existing acceptance services were checked read-only and reused. The Keycloak test realm and local HLS fixture were restored after their temporary test changes.

### OIDC expiration and session invalidation

| Check | Status | Evidence |
|---|---|---|
| Expired OIDC access token renews when the Keycloak session is valid | **PASS** | Only the isolated `streaming` test realm was temporarily set to a 45-second access-token lifespan. Chromium waited past JWT `exp`, triggered Camera refresh through the authenticated UI, observed a different bearer token and received HTTP 200. |
| Logout invalidates browser session and protects `/app` | **PASS** | Keycloak logout followed by `/app` showed the Keycloak login form with no SaaS shell. It remained on one prompt without a redirect loop. |
| Renewal failure after server-side session revocation | **PASS** | A synthetic OWNER Keycloak session was revoked through the isolated admin API. After access-token expiry, Camera refresh did not receive a successful response or a replacement bearer token; browser returned to one login prompt without a loop. Requests were rejected or aborted. |
| Restore test realm configuration | **PASS (separate read-after-write)** | The first test process's admin token expired before its `finally` restore, so it exited nonzero after all scenario assertions passed. A fresh isolated admin token restored the original 300-second lifespan; a subsequent realm read confirmed 300 seconds. The test helper was corrected to obtain a fresh admin token in `finally`; acceptance assertions were not rerun. |

Production Keycloak configuration was not changed. Access tokens were held only in test memory and never printed.

### Playback grant renewal and expiry

| Check | Status | Evidence |
|---|---|---|
| Private HLS continues after renewal | **PASS** | With the configured 90-second grant TTL and a 140-second synthetic H.264 VOD, Chromium decoded beyond 40 seconds and observed two distinct grants. Playlist/segment requests observed through the renewal point all returned HTTP 200. |
| Expired grant is rejected | **PASS** | After the first grant's actual `expiresAt`, the old protected playlist URL fetched through Gateway returned HTTP 401. The already authorized player continued beyond 80 seconds. |
| Permanent URL and iframe contain no playback grant | **PASS (reused)** | Prior browser evidence inspected the permanent URL and copied iframe HTML; neither contained a media grant. This check was not repeated. |

The test generated 70 short HLS segments with one FFmpeg thread in `/tmp`, temporarily replaced the isolated HTTP fixture playlist and restored its original playlist in `finally`. No RTSP connection, ingest pipeline, MediaMTX or load test was involved. Grant values remained in memory and were not logged.

### Responsive layout and clipboard

| Check | Status | Evidence |
|---|---|---|
| Desktop 1440×900 Dashboard sections and watch page | **PASS** | Overview, Cameras, Broadcasts, Members and playback fit without document horizontal overflow; organization switcher remained visible. |
| Tablet 768×1024 Dashboard sections and watch page | **PASS** | Same sections fit without document overflow after the responsive grid correction. |
| Mobile 390×844 Dashboard sections and watch page | **PASS** | Same sections fit without document overflow; camera/member tables retain their own horizontal scroller where their columns exceed the viewport. |
| Permanent watch URL clipboard | **PASS** | Browser clipboard contained the stable PRIVATE watch URL and tenant selector, with no JWT, playback grant or RTSP credential. |
| Invitation URL clipboard | **PASS** | Added a one-time `Copy invitation link` control. Chromium copied the synthetic invitation URL; it contained no JWT, media grant or internal credential. The test invitation was revoked afterward. Plaintext remains in page memory only and is cleared on tenant switch/dismiss. |
| iframe HTML clipboard | **PASS (reused)** | Earlier browser acceptance read the actual clipboard and verified stable URL, no playback grant and `referrerpolicy="no-referrer"`; not repeated. |

The Members view uses inline role selectors and the browser-native `window.confirm` for removal; it has no custom responsive modal. The native confirmation is browser chrome, not dashboard layout. The camera table can scroll inside its own wrapper; the page itself does not overflow.

### Legacy Surveillance frontend

**PASS (isolated frontend configuration only).** A separate Vite process on `127.0.0.1:5175` used `VITE_AUTH_MODE=legacy`. Chromium observed the existing root dashboard shell; `/app` redirected to `/` without a SaaS shell; no Keycloak request was made; `/watch/{syntheticId}` rendered the legacy player route and one video element without Keycloak. Backend API requests were blocked by the browser test, so this does not claim full Surveillance deployment or playback runtime validation. No Surveillance container was started.

### Changes made for failures found

- `StreamingApp.vue`: Invitation URL copy button; clears the one-time URL on workspace change/dismiss. Narrow mobile sidebar and `.stack` grid sizing prevent the selected long workspace name and Camera form from expanding the page.
- `saas-dashboard-final-acceptance.mjs`: exact viewport and clipboard checks. The test blocks only the Gateway host; an earlier `**/api/**` route glob also blocked SPA source modules and was removed.
- Focused browser scripts were added for short-lived session behavior, HLS renewal/expiry and legacy route smoke. No general test framework was introduced.

`npm run build` passed after UI changes (Vue type check and Vite production build; existing ~630 kB chunk advisory remains). `node --check` and `git diff --check` passed. No Gradle regression, Docker build/operation, Kafka/STOMP, Flyway, full RTSP/MediaMTX suite, load test or production ingress was used.

### Remaining limitations and Phase 3.3B decision

- The direct PRIVATE watch page does not provide an in-player sign-in button. For the accepted path, the viewer signs in through `/app` first and then opens the permanent PRIVATE URL. This is deferred UX, not an authorization bypass: anonymous playback remains denied.
- The legacy smoke validated frontend routing and OIDC independence only; it deliberately did not start Surveillance services or verify backend/media runtime.
- Native browser confirmation dialogs are not styled/responsive dashboard dialogs.
- Public ingress, production Keycloak configuration and production data were not touched.

All five remaining Phase 3.3B.2 scenario groups passed: OIDC expiration/session handling, playback grant renewal/expiry, the requested responsive viewports, copy actions, and legacy frontend compatibility. **Phase 3.3B is closed for this acceptance scope.** Phase 3.3C is not implemented, and public ingress remains disabled.
