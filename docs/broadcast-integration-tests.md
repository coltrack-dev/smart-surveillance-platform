# Broadcast MVP integration and load checks

## Status and safety

Validation ran on 2026-11-23 against the isolated `broadcast-it` Compose project. **MediaMTX authentication, browser playback/renewal/revocation, four-viewer load, pre-reboot component recovery and the deterministic 32-slot controller test passed.** The focused controller test held 32 upstream calls, rejected request 33, then admitted another request after slot release. Runtime 32/33/96 batches were nondeterministic and are not the semaphore evidence. Public ingress remains disabled. No existing Surveillance container, volume or database was changed. No images, volumes, containers or build caches were deleted.

The isolated stack uses its own database, network and named volume. It publishes only loopback gateway/player ports 18081/5173; MediaMTX, ingest-agent, Kafka, PostgreSQL and application APIs have no host port mappings. Test-only credentials were held in `/tmp/broadcast-it.env` with mode 0600 and were not written into this repository. The runtime reused local PostgreSQL 17, Kafka 3.9.1, MediaMTX 1.13.1, FFmpeg 7.1.1 and demo images for unchanged services; only the changed broadcast-player image was rebuilt during this validation. The Docker build context for the player was 285.73 KB. The build emitted the pre-existing large-JS-chunk advisory, but completed successfully.

MediaMTX 1.13.1 supports `MTX_AUTHINTERNALUSERS_<index>_*` overrides. The failure was an incomplete nested override: environment configuration reconstructs the user list, and setting only `USER`/`PASS` left each user without permissions. Current streaming Compose supplies every user and permission field. The isolated overlay appends a publisher restricted to `test-source` and an `any` reader restricted to that synthetic path because the Camera test URL intentionally carries no credentials. **Never deploy `docker/mediamtx.broadcast-it.yml` or the `broadcast-it` overlay as a production configuration.** The real streaming roles were verified against HTTP and RTSP allow/deny cases; see the result record below.

The isolated stack uses Compose project `broadcast-it`, its own network, PostgreSQL service and named volume, loopback-only gateway/player ports 18081/5173, and the synthetic `test-publishers` profile. After the host reboot, Docker showed the isolated PostgreSQL container exited and camera-service restarting; this post-reboot state has not been tested or repaired. No recovery command has been run. The overlay shares no volumes or fixed container names with the demo/streaming deployments.

## Start the isolated stack

Populate the required values in the shell or a local `.env` that is not committed. Use random values in local test runs; do not copy production credentials. A 32-byte base64 key can be generated with `openssl rand -base64 32`; other secrets must be at least 32 characters. The example values below are test-only.

```bash
export STREAMING_MEDIA_PUBLISH_USER=it_publish
export STREAMING_MEDIA_PUBLISH_PASSWORD='it_publish_password_change_me'
export STREAMING_MEDIA_READ_USER=it_read
export STREAMING_MEDIA_READ_PASSWORD='it_read_password_change_me'
export VIDEO_INGEST_AGENT_TOKEN='it_agent_token_change_me_0123456789'
export POSTGRES_PASSWORD='it_external_db_password_unused'
export CAMERA_CREDENTIALS_KEY="$(openssl rand -base64 32)"
export BROADCAST_MANAGEMENT_TOKEN='it_management_token_change_me_0123456789'
export BROADCAST_PLAYBACK_SECRET='it_playback_secret_change_me_0123456789'
export BROADCAST_IT_RTSP_PUBLISH_USER=it_source_publisher
export BROADCAST_IT_RTSP_PUBLISH_PASSWORD='it_source_password_change_me'
export BROADCAST_PLAYBACK_TTL=PT75S
export BROADCAST_PLAYER_ORIGIN=http://localhost:5173
export BROADCAST_API_URL=http://127.0.0.1:18081/api

docker compose -p broadcast-it \
  -f docker-compose.streaming.yml \
  -f docker-compose.broadcast-it.yml \
  --profile test-publishers config -q

# Rebuild only the changed images; reuse the installed test/demo images above.
docker compose -p broadcast-it \
  -f docker-compose.streaming.yml \
  -f docker-compose.broadcast-it.yml \
  build stream-service broadcast-player

docker compose -p broadcast-it \
  -f docker-compose.streaming.yml \
  -f docker-compose.broadcast-it.yml \
  --profile test-publishers up -d --no-build
```

These commands are scoped to the isolated project and create only `broadcast-it` resources. The test camera DB requires its minimal pre-existing schema fixture (`docker/broadcast-it-camera-schema.sql`) before the camera service Flyway V1 migration can validate. Wait for gateway, player, camera-service, stream-service and agent readiness. Check status with `docker compose -p broadcast-it -f docker-compose.streaming.yml -f docker-compose.broadcast-it.yml ps`.

## End-to-end test

The script creates a test camera and Broadcast in the isolated database, exercises API and playback, then deletes those test records in a `finally` cleanup. It does not clean up the project, network, containers or named volume.

```bash
export BROADCAST_MANAGEMENT_TOKEN='it_management_token_change_me_0123456789'
python3 scripts/broadcast-integration-test.py
```

The script checks API registration, auth policy, idempotency, permanent URL generation, token tampering/expiry/revocation, safe paths, protected playlist/segment access, and overlap between Broadcast and legacy Stream API leases. It deletes only the temporary camera and Broadcast records in its `finally` block.

The auth check verifies HTTP HLS and RTSP allow/deny cases against MediaMTX with correct, absent and wrong credentials; confirms publish works for the publisher role and fails for the read-only role; and scans isolated logs for MediaMTX credentials. It also verifies the agent readiness role can read its UUID path. The anonymous test `test-source` rule exists only inside this isolated network.

Install and run the Chromium test (Playwright's browser is stored under `/tmp`; Firefox/Snap is not used):

```bash
cd frontend
PLAYWRIGHT_BROWSERS_PATH=/tmp/broadcast-playwright-browsers npx playwright install chromium
PLAYWRIGHT_BROWSERS_PATH=/tmp/broadcast-playwright-browsers npm run test:broadcast:e2e
```

The browser test creates a temporary PUBLIC Broadcast, checks its permanent watch and generated iframe URLs, then decodes H.264 frames in both page and iframe. The isolated TTL is 75 seconds; it verifies current-time progress through a 90-second renewal period, rejection of the expired first token, no token in checked container logs or referrer headers, and stable FFmpeg PID across renewal. It disables the Broadcast while playback is live and checks the offline UI and rejected token renewal. It runs the four-viewer test and 32/33/96 parallel segment batches after the renewal check. It cleans up only its test rows.

## Ownership and recovery checks

The integration script covers overlapping Broadcast and legacy Stream API leases. Observed recovery results:

1. **Passed:** Broadcast and legacy Stream API shared one camera session. Disabling Broadcast left the legacy Stream API consumer running; stopping that consumer released only its lease.
2. **Passed:** the recovery script restarted isolated stream-service, ingest-agent, MediaMTX and PostgreSQL in turn. Protected HLS returned after each restart and `docker top` showed exactly one FFmpeg process for the source each time.
3. **Not run:** separate camera network disconnect/reconnect injection with timed recovery. Publisher reconnect after MediaMTX restart does not prove an independent source network outage.

Browser viewers do not acquire persistent StreamManager leases in this implementation. The enabled Broadcast owns one durable consumer lease; disabling/deleting it releases that lease. Viewer authorization is short-lived and revocable by Broadcast generation, so a disconnected viewer leaves no lease row to become stale. Legacy Stream API and camera auto-start leases are consumer-scoped and independent.

Never execute these commands without the explicit `-p broadcast-it` and both compose files. Do not use `docker compose down`, `down -v`, `rm`, `volume rm`, or broad `docker system prune` as part of validation.

## Bounded viewer/load check

Run the load script below only while the isolated stack is serving an enabled PUBLIC Broadcast. The script defaults to 4 viewers for 60 seconds, caps at 16 viewers and 300 seconds, requests each viewer's playlist and newest media segment, and reports request latency, bytes, throughput and failures. It does not measure glass-to-glass latency. The final browser run returned HTTP measurements but did not include the newer before/after Docker stats fields in its recorded output; its CPU/RAM for that run is not recorded.

```bash
python3 scripts/broadcast-load-test.py \
  --base-url http://127.0.0.1:18081 \
  --public-id PUBLIC_UUID \
  --viewers 4 --duration 60

docker stats --no-stream \
  broadcast-it-stream-service-1 \
  broadcast-it-video-ingest-agent-1 \
  broadcast-it-mediamtx-1 \
  broadcast-it-broadcast-player-1

docker top broadcast-it-video-ingest-agent-1
```

Do not extrapolate these single-machine results to a production viewer limit. The proxy has a 32-request in-flight upstream semaphore and a separate in-process IP request limit; replicas do not share counters. The live batches were too fast and nondeterministic to confirm the semaphore boundary. The deterministic controller test is the only pending limit check.

## Compose and automated checks

```bash
docker compose --env-file /tmp/broadcast-it.env -f docker-compose.demo.yml config -q
docker compose --env-file /tmp/broadcast-it.env -f docker-compose.streaming.yml config -q
npm --prefix frontend run build
npm --prefix frontend audit
npm --prefix frontend audit --omit=dev
git diff --check
```

All three Compose config checks passed, as did the frontend production build and both npm audits (0 vulnerabilities each). The existing Gradle XML reports 23 tests, 0 failures/errors, timestamped 14:37; it predates the new concurrency test. The later Gradle run used `/tmp/broadcast-phase21-gradle-home`, downloaded Gradle 8.14.2 and reached task-graph calculation, but was interrupted by reboot and produced no fresh test result.

## Result record

Environment: Ubuntu guest, Linux 7.0.0-43-generic x86_64, Docker Engine 29.1.3, 2026-11-23. Synthetic source: 640×360 H.264 at 10 fps; Chromium 159.0.8154.6; playback TTL `PT75S`; viewer load 4 for 30 seconds. Passing scripts: `broadcast-integration-test.py`, `broadcast-mediamtx-auth-check.py`, `broadcast-recovery-test.py`, and Playwright `test:broadcast:e2e`. The interrupted semaphore test has no fresh XML result.

| Check | Observed result |
| --- | --- |
| Compose validation (demo / streaming / isolated) | PASS: all three `docker compose config -q` commands |
| Prior stream-service test report | 23 tests, 0 failures/errors; XML timestamp 14:37, before the new concurrency test |
| New deterministic semaphore test | PASS per completed-run report: only `capsConcurrentUpstreamRequestsAt32AndReleasesSlotsAfterCompletion`; Gradle 8.14.2, 1m25s, 13 tasks (2 executed, 11 up-to-date), BUILD SUCCESSFUL; Configuration Cache saved |
| Frontend build and dependency audit | PASS: build completed; full and production npm audits each reported 0 vulnerabilities |
| MediaMTX auth roles | PASS: reader HTTP HLS and RTSP read succeeded; missing/wrong HTTP credentials returned 401; unauthenticated RTSP read failed; publisher published; reader could not publish |
| Credential log scan | PASS: MediaMTX publish/read passwords absent from MediaMTX, agent, stream-service and synthetic publisher logs |
| Camera / protected HLS pipeline | PASS: synthetic 640×360 H.264 source and protected playlist/segments served |
| Permanent watch / iframe | PASS: stable watch URL and generated iframe URL; `referrerpolicy="no-referrer"` |
| Chromium page and iframe playback | PASS: decoded 640×360 frames; 10 frames at each initial decode check |
| Token expiry / renewal / revocation | PASS: playback time advanced 90 seconds during a 90-second renewal interval; expired first token returned 401; disable caused offline UI and rejected token issuance; 10 token requests observed |
| Token/referrer log protection | PASS: no token-bearing Referer; first playback token and MediaMTX passwords absent from checked logs |
| FFmpeg continuity | PASS: one FFmpeg PID before/after renewal; PID `473327` in final recorded browser run |
| Stream lease compatibility | PASS: disabling Broadcast did not stop the independent legacy Stream API consumer |
| Restart recovery before host reboot | PASS: stream-service, ingest-agent, MediaMTX and PostgreSQL each recovered protected HLS with one FFmpeg process |
| Camera network disconnect/reconnect | NOT RUN independently |
| Four-viewer load | PASS: 4 viewers / 30.23 seconds; 128 requests, 0 failures; 125,080 bytes |
| Request latency / throughput | p50 6.56 ms; p95 11.04 ms; 4.04 KiB/s HTTP response payload. Not glass-to-glass latency or capacity |
| Runtime 32/33/96 concurrent batches | Final live run: 32×200, 33×200, 96×200; inconclusive due to quick responses. Deterministic controller test above verifies 32 occupied slots, overflow rejection and slot release |
| CPU/RAM for final browser load | NOT RECORDED in its output. Older snapshots are from a separate earlier run and are not attributed to this run |
| Earlier 4-viewer snapshot retained from prior report | stream-service 2.27% / 341.6 MiB → 1.43% / 349.3 MiB; agent 0.24% / 66.42 MiB → 0.22% / 67.67 MiB; MediaMTX 1.37% / 16.39 MiB → 1.40% / 16.5 MiB; player 0% / 5.84 MiB → 0% / 5.84 MiB. This is not the final browser run |
| Post-host-reboot isolated stack | UNKNOWN / NOT RUN: Docker auto-started part of the project; PostgreSQL was exited and camera-service was restarting when inspected |
| Reboot diagnosis | orderly `systemd-reboot.service` at 15:47:53–15:47:58; no OOM kill, kernel panic, thermal shutdown or watchdog incident found. Initiating request is unknown |

## Limitations / Phase 3 prerequisites

This test mode is single-operator and single `stream-service` instance. Public ingress is disabled. MediaMTX auth and role separation passed in the isolated config, but the separate test config contains a test-source-only anonymous reader and must not be deployed. Private viewer/invitation authorization is not implemented; rate limiting and the 32-request semaphore are process-local. Separate camera network disconnect/reconnect and post-host-reboot stack recovery remain untested. Phase 3 still needs tenant/user authorization, durable distributed ownership, observability/recovery SLOs, protected public routes, ingress log redaction, browser/device coverage, target-scale concurrency and backup/restore validation.

### Audit of the seven baseline high-severity package findings

The preserved pre-upgrade frontend lockfile produced seven npm high-severity package groups. Current full and production-only audits are clean. Compatible remediations:

| Package group | Finding class | Compatible remediation |
| --- | --- | --- |
| `@vue/server-renderer` | XSS from incomplete attribute-name filtering | Upgrade Vue to 3.5.42 or newer; 3.5.46 is non-major and includes the renderer fix. |
| `vue` | Affected Vue 3.2.13–3.5.41 dependency chain | Upgrade to 3.5.46 and keep Vue/renderer versions aligned. |
| `axios` | Multiple prototype-pollution, ReDoS/DoS, proxy/DNS and redirect/SSRF issues through 1.19.0 | Upgrade within 1.x to 1.20.0 or newer and constrain the package range to prevent re-locking 1.19.0. |
| `follow-redirects` | Sensitive headers/credentials can survive cross-origin redirects in 1.16.0–1.16.1 | Upgrade transitive resolution to 1.16.2 or newer. |
| `nanoid` | Custom generator can loop indefinitely for zero-size requests | Upgrade to 3.3.18 or newer in the existing major line. |
| `source-map-js` | Indexed source-map offsets can cause event-loop denial of service | Upgrade to 1.2.2 or newer. |
| `vite` | Vite/launch-editor command injection and middleware/path disclosure issues in 8.2.0–8.2.3 | Upgrade within 8.x to 8.3.8 or newer; avoid forced major upgrades. |

Vite, `nanoid` and `source-map-js` findings are primarily build/development exposure. Axios, Vue and redirect handling can be in production dependency paths. The current `npm audit --omit=dev` is clean, so no production package finding remains in the current lockfile.
