# Deployment modes

## Phase 3.2A identity update

Streaming now includes an isolated Keycloak development realm and identity-service. Keycloak is pinned to `quay.io/keycloak/keycloak:26.8.2`, uses its own PostgreSQL database and volume, and publishes its development HTTP/admin UI only on loopback. The Vue client `streaming-vue` uses Authorization Code + PKCE S256; the API audience is `streaming-api`. Set the browser-visible issuer/base URL and SPA redirect/origin consistently. The imported realm is development-only and has no seeded user or password.

Streaming Camera/Stream data now uses a dedicated `streaming-postgres` service and `streaming-db-data` volume; it no longer defaults to the Surveillance database or existing external data. Identity-service owns its additive `identity` schema migration. No existing Camera/Broadcast data is copied and no migration was executed in Phase 3.2A. The initial identity schema has no tenant/member records and there is no membership provisioning endpoint.

In Streaming, gateway and stream-service management APIs require a valid OIDC access token; the identity API is also authenticated. User/tenant role authorization and Camera/Broadcast ownership are not implemented yet. Therefore this is a private development foundation only: keep gateway/player/Keycloak loopback-bound and do not enable public ingress. PUBLIC Broadcast playback remains governed by the existing explicit PUBLIC policy; PRIVATE member playback is not implemented. Surveillance keeps explicit legacy gateway/stream mode and does not include Keycloak or identity-service.

## Assessment

The supported baseline is `docker-compose.demo.yml`; the root `docker-compose.yml` is an older development stack (different Kafka listeners, no current application services) and is not part of either deployment recipe. The project remains one codebase and shares the same Camera, Stream, ingest-agent, Kafka, and MediaMTX implementation. The streaming Compose file is a smaller deployment assembly, not a second implementation of those capabilities.

| Classification | Services | Purpose / coupling |
| --- | --- | --- |
| Shared platform | `kafka`, `mediamtx`, `video-ingest-agent`, `camera-service`, `stream-service`, `websocket-service`, `camera-gateway` | Metadata and encrypted camera credentials; event transport; RTSP ingest and HLS; Broadcast resource/API is implemented inside `stream-service`; API and socket entry point. Both modes use these same code modules. |
| Surveillance-only | `recording-service`, `analytics-service`, `inference-worker`, `volume-permissions`, `rtsp-test-publisher`, `rtsp-file-publisher`, `rtsp-file-publisher-2`, `rtsp-i287-publisher` | Recording/export/playback, analytic jobs/inference, storage permissions, and demo/test inputs. |
| Streaming-specific | `streaming-postgres`, `keycloak-postgres`, `keycloak`, `identity-service`, `broadcast-player` | Isolated Streaming and identity persistence, OIDC provider/dev realm, identity lookup API and standalone player. No duplicate Camera/Stream/Ingest service is added. |

### Service dependency matrix

| Service | Depends on | PostgreSQL | Public ingress in streaming mode |
| --- | --- | --- | --- |
| Kafka | — | No | No; internal listener only |
| MediaMTX | — | No | No; `expose`, no host ports, internal auth |
| streaming-postgres | — | Yes (Streaming camera/identity data) | No; no host port |
| keycloak-postgres | — | Yes (IdP state) | No; no host port |
| keycloak | keycloak-postgres | Yes | Development/admin UI bound to loopback only |
| video-ingest-agent | MediaMTX | No | No; internal network and token protected |
| camera-service | Kafka | Yes (isolated Streaming database) | Via OIDC-authenticated gateway only; not tenant-scoped yet |
| stream-service | Kafka, camera-service, MediaMTX, ingest-agent | Yes (isolated Streaming database) | OIDC for management; playback uses existing Broadcast policy |
| websocket-service | Kafka | No | Via gateway only; add authorization before public use |
| identity-service | streaming-postgres, Keycloak JWKS | Yes | Via authenticated gateway only |
| camera-gateway | Camera, Stream, Identity, MediaMTX HLS, WebSocket | No | Loopback port 8081; OIDC in Streaming, legacy trusted access in demo |
| broadcast-player | camera-gateway playback proxy | No | Loopback port 5173 by default; permanent watch URL obtains renewed short-lived media auth |
| recording-service | Kafka, Camera, Stream | Yes | Not present |
| analytics-service | Kafka, Camera | Yes | Not present by default (demo profile `container-analytics`) |
| inference-worker | Kafka, Recording | No | Not present by default (demo profile `inference`) |
| RTSP publishers | MediaMTX | No | Not present in streaming mode; demo-only inputs |

PostgreSQL is deliberately external in demo mode; the commented `postgres` service and its named volume stay disabled. Existing DB data and volumes are untouched. Streaming mode has a dedicated application database and a separate Keycloak database, each with Streaming-only volumes. Do not reuse Surveillance data or volumes.

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

For Streaming create local `.env` values for `STREAMING_DB_PASSWORD`, `KEYCLOAK_DB_PASSWORD`, `KEYCLOAK_ADMIN_USERNAME`, `KEYCLOAK_ADMIN_PASSWORD`, `STREAMING_MEDIA_PUBLISH_PASSWORD`, `STREAMING_MEDIA_READ_PASSWORD`, `VIDEO_INGEST_AGENT_TOKEN`, `CAMERA_CREDENTIALS_KEY`, and `BROADCAST_PLAYBACK_SECRET`. Use unique random secrets; the `.env.example` leaves these values empty. Generate the camera key using `openssl rand -base64 32`. Configure `STREAMING_OIDC_BASE_URL`, `STREAMING_OIDC_ISSUER_URI`, `STREAMING_OIDC_REDIRECT_URI` and `BROADCAST_PLAYER_ORIGIN` to the matching browser URLs. Defaults are issuer `http://localhost:8180/realms/streaming`, audience `streaming-api`, client `streaming-vue`, player origin `http://localhost:5173` and callback `http://localhost:5173/`. Keycloak admin UI is loopback-only at `http://localhost:8180`; create a development user through its admin UI. The gateway and player bind to `127.0.0.1` by default. Browser login is not live-validated in Phase 3.2A. Management is authenticated but not yet tenant/role-authorized. The existing playback token flow allows only enabled PUBLIC broadcasts anonymously; PRIVATE viewer membership playback is not available. Do not expose this deployment publicly.

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
- Streaming provisions its isolated PostgreSQL databases/volumes in Compose. Camera schema bootstrap and service migrations run only against this Streaming database. Identity-service has a separate Flyway history table in the `identity` schema. No existing rows are copied; tenant ownership migration/backfill is not implemented.
- Avoid renaming or deleting the old Compose volumes. The streaming volume names are intentionally separate; migrating Kafka/HLS/recording data is not part of this phase.
- The gateway's legacy `/media-hls/**` route proxies MediaMTX content and is not the Broadcast player route; Streaming OIDC mode denies `/media-hls/**`, `/hls/**` and `/ws/**` at the gateway. OIDC protects Streaming management ingress, while Surveillance remains trusted-network-only. Neither mode is suitable for public ingress as-is.
- Broadcast persistence remains inside stream-service. The streaming setup uses a distinct database; Surveillance's DB and named volumes are unchanged. Demo still uses its legacy management secret. Streaming management uses OIDC gateway authentication; `BROADCAST_PLAYBACK_SECRET` remains for short-lived playback grants. Rotating it invalidates issued grants. The demo mode has development defaults and should not be exposed as a public service.
- Broadcast playback uses `/broadcast-play/{publicId}/{file}?token=...`; the general `/media-hls/**` route remains unsuitable for public playback. The standalone player runs on port 5173, and permanent links default to `http://localhost:5173/watch/{publicId}`. Token query parameters must be redacted from ingress logs; player and iframe use `no-referrer`. Configure `BROADCAST_API_URL` and `BROADCAST_PLAYER_BASE_URL` to match the private browser-accessible endpoints.
- The streaming Compose file does not include a synthetic publisher. Use an isolated local RTSP source for smoke tests; do not add publishers to the default deployment assembly.

## Streaming SaaS MVP roadmap

1. **Identity and tenant boundary:** replace the single management bearer secret with user login, organization/tenant membership and roles; derive tenant identity from verified auth claims, never request-supplied tenant IDs. Scope Broadcast, Camera and Stream records and every query/mutation by tenant. Add tenant-aware migrations and data backfill strategy before onboarding.
2. **Commercial control plane:** add subscription state, plan limits, per-tenant quotas, billing/webhook idempotency, usage metering, audit history, tenant-aware provisioning, and deletion/export. Keep Camera as camera metadata and Stream as ingest/runtime representation; do not create duplicate camera/stream/ingest models.
3. **Ingest credentials and isolation:** replace shared MediaMTX credentials with short-lived or per-stream credentials and server-side authorization callbacks (or isolated credential domains). Scope publish/read permissions to stream paths and tenant; rotate credentials, audit access, rate-limit management APIs, and protect agent endpoints. Do not send MediaMTX admin credentials to browsers.
4. **Production playback:** provision TLS and public ingress, validate entitlement for every playback token, add revocation, origin policy, distributed rate limits, and CDN cache behavior that respects tenant authorization. Keep every playlist, segment and key protected; do not expose direct unauthenticated MediaMTX paths.
5. **SaaS operations:** add billing/subscription state and webhook idempotency, usage metering, quotas, retention policy, audit logs, health/SLO metrics, alerting, backups, and per-tenant deletion/export. Validate tenant isolation, token expiry/revocation, and abuse limits before public launch.
6. **Scale and deployment:** only after MVP behavior is proven, decide media-node scheduling, geographic placement, HA Kafka/PostgreSQL, object storage/CDN, and deployment topology. Kubernetes is not required for this architecture preparation.

## Broadcast API and local smoke test

Broadcast management is under `POST/GET /api/v1/broadcasts`, `GET /api/v1/broadcasts/{id}`, `PUT /api/v1/broadcasts/{id}/enabled` (`{"enabled":true|false}`), `PUT /api/v1/broadcasts/{id}/visibility`, `DELETE /api/v1/broadcasts/{id}`, and `GET /api/v1/broadcasts/{id}/playback`. Send `Authorization: Bearer $BROADCAST_MANAGEMENT_TOKEN` in demo mode or a Keycloak access token in Streaming mode. Creating for the same camera is idempotent. Playback details contain a permanent player URL and iframe markup. `GET /api/playback/{publicId}/token` returns the current short-lived authorization; the browser player renews it. The media URLs do not contain source RTSP credentials. For isolated integration commands and honest result recording, see [broadcast-integration-tests.md](broadcast-integration-tests.md).

For a local source smoke test after configuring secrets and signing in to `streaming-vue`, use a local RTSP source reachable by the ingest-agent. The Streaming Compose file does not define a synthetic publisher.

```bash
export STREAMING_ACCESS_TOKEN='short-lived access token from the local streaming-vue session'
curl -sS http://127.0.0.1:8081/api/v1/identity/me \
  -H "Authorization: Bearer $STREAMING_ACCESS_TOKEN"
```

Create a camera through the private gateway (replace the sample URL with the reachable source):

```bash
curl -sS -X POST http://127.0.0.1:8081/api/v1/cameras \
  -H "Authorization: Bearer $STREAMING_ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Broadcast smoke source","rtspUrl":"rtsp://local-source/live","rtspUrlFormat":"STANDARD","videoProcessingMode":"AUTO","autoStart":false}'
```

Use the returned camera `id` for the management flow:

```bash
curl -sS -X POST http://127.0.0.1:8081/api/v1/broadcasts \
  -H "Authorization: Bearer $STREAMING_ACCESS_TOKEN" \
  -H 'Content-Type: application/json' -d '{"cameraId":"CAMERA_UUID"}'
curl -sS -X PUT http://127.0.0.1:8081/api/v1/broadcasts/BROADCAST_UUID/enabled \
  -H "Authorization: Bearer $STREAMING_ACCESS_TOKEN" \
  -H 'Content-Type: application/json' -d '{"enabled":true}'
curl -sS http://127.0.0.1:8081/api/v1/broadcasts/BROADCAST_UUID/playback \
  -H "Authorization: Bearer $STREAMING_ACCESS_TOKEN"
```

Open the returned permanent `playerUrl`; use `embedHtml` in an iframe. The player renews the temporary HLS token automatically. Disable or delete the Broadcast to invalidate active playback. StreamManager consumer leases ensure disabling a Broadcast does not stop a stream that Surveillance or another consumer still uses.
