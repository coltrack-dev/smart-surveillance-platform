# Deployment modes

## Assessment

The supported baseline is `docker-compose.demo.yml`; the root `docker-compose.yml` is an older development stack (different Kafka listeners, no current application services) and is not part of either deployment recipe. The project remains one codebase and shares the same Camera, Stream, ingest-agent, Kafka, and MediaMTX implementation. The streaming Compose file is a smaller deployment assembly, not a second implementation of those capabilities.

| Classification | Services | Purpose / coupling |
| --- | --- | --- |
| Shared platform | `kafka`, `mediamtx`, `video-ingest-agent`, `camera-service`, `stream-service`, `websocket-service`, `camera-gateway` | Metadata and encrypted camera credentials; event transport; RTSP ingest and HLS; Broadcast resource/API is implemented inside `stream-service`; API and socket entry point. Both modes use these same code modules. |
| Surveillance-only | `recording-service`, `analytics-service`, `inference-worker`, `volume-permissions`, `rtsp-test-publisher`, `rtsp-file-publisher`, `rtsp-file-publisher-2`, `rtsp-i287-publisher` | Recording/export/playback, analytic jobs/inference, storage permissions, and demo/test inputs. |
| Streaming-specific | `broadcast-player`; optional `rtsp-test-publisher` under `test-publishers` profile | Standalone public player UI and a synthetic input for local smoke tests. No additional business microservice is needed for the MVP. |

### Service dependency matrix

| Service | Depends on | PostgreSQL | Public ingress in streaming mode |
| --- | --- | --- | --- |
| Kafka | — | No | No; internal listener only |
| MediaMTX | — | No | No; `expose`, no host ports, internal auth |
| video-ingest-agent | MediaMTX | No | No; internal network and token protected |
| camera-service | Kafka | Yes | Via gateway only (gateway currently has no auth) |
| stream-service | Kafka, camera-service, MediaMTX, ingest-agent | Yes (Broadcast table) | Broadcast management requires Bearer token; playback requires short-lived signed token |
| websocket-service | Kafka | No | Via gateway only; add authorization before public use |
| camera-gateway | Camera, Stream, MediaMTX HLS, WebSocket | No | Loopback port 8081 by default; do not widen before all existing APIs have auth |
| broadcast-player | camera-gateway playback proxy | No | Loopback port 5173 by default; share links are time-limited |
| recording-service | Kafka, Camera, Stream | Yes | Not present |
| analytics-service | Kafka, Camera | Yes | Not present by default (demo profile `container-analytics`) |
| inference-worker | Kafka, Recording | No | Not present by default (demo profile `inference`) |
| RTSP publishers | MediaMTX | No | Not present in streaming mode; demo-only inputs |

PostgreSQL is deliberately external in demo mode; the commented `postgres` service and its named volume stay disabled. Existing DB data and volumes are untouched. Streaming mode uses the same external database host (default `host.docker.internal`) and existing camera schema. Use a database and credentials intended for this deployment; do not point a new environment at production data until migrations and tenant isolation have been reviewed.

## Run modes

Copy `.env.example` to `.env` and set secrets/host values for the selected environment. Compose project names are distinct. Do not run the two projects against the same named volumes or the same host ports unless intentionally planned.

Surveillance demo:

```bash
docker compose -p surveillance-demo -f docker-compose.demo.yml config
docker compose -p surveillance-demo -f docker-compose.demo.yml up -d
```

Enable optional analytics/inference services separately (the test profile change is proposed below, not applied):

```bash
docker compose -p surveillance-demo -f docker-compose.demo.yml --profile container-analytics --profile inference up -d
```

Streaming foundation (includes Broadcast management and the player):

```bash
docker compose -p surveillance-streaming -f docker-compose.streaming.yml config
docker compose -p surveillance-streaming -f docker-compose.streaming.yml up -d
```

For streaming set the MediaMTX publish/read credentials, `VIDEO_INGEST_AGENT_TOKEN`, `POSTGRES_PASSWORD`, `CAMERA_CREDENTIALS_KEY`, `BROADCAST_MANAGEMENT_TOKEN`, and `BROADCAST_PLAYBACK_SECRET` to deployment secrets. Use URL-safe publish credentials (letters, digits, underscore, hyphen) because they are embedded in the RTSP URL. Generate the camera key using `openssl rand -base64 32`. The gateway is reachable at `http://127.0.0.1:8081` and the player at `http://127.0.0.1:5173` by default. Broadcast management requires its Bearer secret; playback links use short-lived HMAC tokens, and playlists plus segments/keys are proxied through token validation. This is a single-operator MVP, not multi-user or tenant authorization. No public production ingress or TLS is configured. Keep both bindings on loopback/private ingress; do not expose MediaMTX, agent, Kafka, or service ports.

The demo's existing RTSP/HLS host ports, Kafka listener, fixed container names, and project name are retained by default for compatibility. `POSTGRES_HOST`, `POSTGRES_PORT`, `KAFKA_ADVERTISED_HOST`, `KAFKA_EXTERNAL_PORT`, `KAFKA_BIND_ADDRESS`, `MEDIAMTX_BIND_ADDRESS`, `MEDIAMTX_RTSP_PORT`, `MEDIAMTX_HLS_PORT`, `GATEWAY_BIND_ADDRESS`, and `ANALYTICS_SERVICE_URL` let operators replace machine-specific addresses/ports. For demo use across a trusted LAN, configure the correct advertised Kafka address and firewall the host ports; the current demo MediaMTX configuration has no auth, so do not expose its RTSP/HLS ports to the public internet.

### Profiles, names, networking, and data

- Compose profiles are opt-in. `analytics-service` is under `container-analytics`; `inference-worker` is under `inference`. Demo publishers currently start by default. Proposed follow-up: add profile `test-publishers` to all four publisher services and document `--profile test-publishers`; apply only after confirming nobody relies on the current default sample streams.
- `container_name` values are fixed in the demo file, so two copies of that project cannot coexist. The standalone streaming file omits fixed names and uses its own project-scoped network and volumes.
- Demo Kafka advertises an external listener for host-side clients; containers use `kafka:29092`. Streaming Kafka has only an internal listener. Do not use `localhost` as an address from another container.
- Demo named volumes (`kafka-data`, `hls-data`, `recording-data`, `playback-cache`, inference volumes) are preserved. Streaming uses distinct `streaming-*` volume names.
- Compose `depends_on` startup ordering is not application readiness except where an explicit health condition exists. Kafka/database reconnect behavior and migrations still need operational monitoring.

## Compatibility and migration notes

- The demo database host default remains `192.168.13.128`, Kafka's advertised host remains `192.168.13.128`, and existing demo ports remain unchanged. Override the new variables in `.env` for other machines.
- Camera and Recording application defaults now honor `SPRING_DATASOURCE_URL`, credentials, and host/port/database variables; Camera, Recording, and Stream service URLs and Kafka bootstrap values are also configurable. Existing standalone defaults remain available for local development.
- Streaming requires an existing compatible PostgreSQL database for Camera metadata. It does not provision or migrate a database automatically beyond the Camera service's existing Flyway migrations. Tenant schema/data migration is not implemented.
- Avoid renaming or deleting the old Compose volumes. The streaming volume names are intentionally separate; migrating Kafka/HLS/recording data is not part of this phase.
- The gateway's legacy `/media-hls/**` route proxies MediaMTX content and is not the Broadcast player route. MediaMTX credentials and the Broadcast token proxy protect Broadcast playback; existing camera/stream/recording API routes do not have general user authentication. Keep the gateway private until those routes are secured too.
- Broadcast persistence adds a `broadcasts` table and `flyway_stream_schema_history` to the existing PostgreSQL database. It does not modify Camera/Recording tables or named Docker volumes. Set stable `BROADCAST_MANAGEMENT_TOKEN` and `BROADCAST_PLAYBACK_SECRET` values; rotating the latter invalidates issued links. Enable/disable also increments a playback generation, so old links stay invalid if a Broadcast is re-enabled. The demo mode has development defaults and should not be exposed as a public service.
- Public Broadcast playback uses `/broadcast-play/{id}/{file}?token=...`; the general `/media-hls/**` route remains unsuitable for shared/public playback. The standalone player runs on port 5173, and player links default to `http://localhost:5173/player` for local testing. Configure `BROADCAST_API_URL` and `BROADCAST_PLAYER_BASE_URL` to match the private browser-accessible endpoints.
- The streaming Compose file contains an optional synthetic RTSP source under profile `test-publishers`; it is omitted by default.

## Streaming SaaS MVP roadmap

1. **Identity and tenant boundary:** replace the single management bearer secret with user login, organization/tenant membership and roles; derive tenant identity from verified auth claims, never request-supplied tenant IDs. Scope Broadcast, Camera and Stream records and every query/mutation by tenant. Add tenant-aware migrations and data backfill strategy before onboarding.
2. **Commercial control plane:** add subscription state, plan limits, per-tenant quotas, billing/webhook idempotency, usage metering, audit history, tenant-aware provisioning, and deletion/export. Keep Camera as camera metadata and Stream as ingest/runtime representation; do not create duplicate camera/stream/ingest models.
3. **Ingest credentials and isolation:** replace shared MediaMTX credentials with short-lived or per-stream credentials and server-side authorization callbacks (or isolated credential domains). Scope publish/read permissions to stream paths and tenant; rotate credentials, audit access, rate-limit management APIs, and protect agent endpoints. Do not send MediaMTX admin credentials to browsers.
4. **Production playback:** provision TLS and public ingress, validate entitlement for every playback token, add revocation, origin policy, distributed rate limits, and CDN cache behavior that respects tenant authorization. Keep every playlist, segment and key protected; do not expose direct unauthenticated MediaMTX paths.
5. **SaaS operations:** add billing/subscription state and webhook idempotency, usage metering, quotas, retention policy, audit logs, health/SLO metrics, alerting, backups, and per-tenant deletion/export. Validate tenant isolation, token expiry/revocation, and abuse limits before public launch.
6. **Scale and deployment:** only after MVP behavior is proven, decide media-node scheduling, geographic placement, HA Kafka/PostgreSQL, object storage/CDN, and deployment topology. Kubernetes is not required for this architecture preparation.

## Broadcast API and local smoke test

Broadcast management is under `POST/GET /api/v1/broadcasts`, `GET /api/v1/broadcasts/{id}`, `PUT /api/v1/broadcasts/{id}/enabled` (`{"enabled":true|false}`), `DELETE /api/v1/broadcasts/{id}`, and `GET /api/v1/broadcasts/{id}/playback`. Send `Authorization: Bearer $BROADCAST_MANAGEMENT_TOKEN` for every management request. Creating for the same camera is idempotent. Playback details contain an expiring playlist URL, player URL and iframe markup. The media URLs do not contain source RTSP credentials.

For a local source smoke test after configuring secrets and the PostgreSQL camera schema:

```bash
docker compose -p surveillance-streaming -f docker-compose.streaming.yml --profile test-publishers up -d
```

Create a camera through the private gateway using the generated source inside the Compose network (no source credentials):

```bash
curl -sS -X POST http://127.0.0.1:8081/api/v1/cameras \
  -H 'Content-Type: application/json' \
  -d '{"name":"Broadcast smoke source","rtspUrl":"rtsp://mediamtx:8554/test-source","rtspUrlFormat":"STANDARD","videoProcessingMode":"AUTO","autoStart":false}'
```

Use the returned camera `id` for the management flow:

```bash
export BROADCAST_MANAGEMENT_TOKEN='your configured management secret'
curl -sS -X POST http://127.0.0.1:8081/api/v1/broadcasts \
  -H "Authorization: Bearer $BROADCAST_MANAGEMENT_TOKEN" \
  -H 'Content-Type: application/json' -d '{"cameraId":"CAMERA_UUID"}'
curl -sS -X PUT http://127.0.0.1:8081/api/v1/broadcasts/BROADCAST_UUID/enabled \
  -H "Authorization: Bearer $BROADCAST_MANAGEMENT_TOKEN" \
  -H 'Content-Type: application/json' -d '{"enabled":true}'
curl -sS http://127.0.0.1:8081/api/v1/broadcasts/BROADCAST_UUID/playback \
  -H "Authorization: Bearer $BROADCAST_MANAGEMENT_TOKEN"
```

Open the returned `playerUrl`; use `embedHtml` in an iframe. The authorization expires after `BROADCAST_PLAYBACK_TTL` (10 minutes by default), so obtain a fresh playback URL when it expires. Disable or delete the Broadcast to invalidate playback and clean up a stream that Broadcast itself started. An already-running surveillance stream is left running.
