# Broadcast MVP architecture and hardening

## Pipeline and decisions

The MVP keeps Camera and Stream as the authoritative models and adds Broadcast as a playback policy/resource referencing a Camera/Stream ID:

```text
RTSP source -> stream-service StreamManager -> Rust ingest-agent/FFmpeg
            -> private MediaMTX publish path -> MediaMTX HLS
            -> stream-service authenticated proxy -> gateway -> watch page / iframe
```

`stream-service` owns Broadcast management because it already owns Stream lifecycle, has access to the existing Camera connection API and proxies HLS. A separate business service would duplicate lifecycle orchestration without improving the single-operator MVP. No second ingest worker is launched for a camera: the manager synchronizes acquire/release and reserves the per-camera session before starting a worker.

### Stream ownership

`stream_consumer_leases` records durable desired consumers keyed by camera and consumer ID. Broadcast uses `broadcast:{broadcastId}`, Camera auto-start uses `camera-auto-start`, and old Stream API start/stop calls use `legacy-stream-api`. Leases are independent; release stops the worker only when no leases remain. Repeated acquisition is idempotent. An enabled Broadcast is reconciled from its database row every five seconds, allowing stream-service restart recovery without creating another worker. Ingest-agent and MediaMTX restarts are recovered by the existing worker health/reconnect loop.

Legacy Stream API compatibility: `POST /api/v1/streams/{cameraId}/start` acquires the singleton legacy lease; `POST .../stop` releases only that lease. Camera auto-start and Broadcast consumers continue. Since legacy callers have no durable caller identity, legacy leases are cleared when stream-service starts; their old in-memory stream state was not restart-persistent either. A single `stream-service` replica is required: the lease table is not a distributed leader lock and duplicate managers across replicas are unsupported.

The Broadcast table remains in the stream-service Flyway history. Migration V2 adds a stable opaque `public_id`, visibility and the lease table, and converts previously enabled Broadcast rows into durable leases. The old `owns_stream` database column is retained as inert legacy data; application code no longer uses it. No Camera or Recording data is rewritten. Back up the target DB and confirm a streaming-only database/schema before applying migrations; do not point this MVP at a production DB without a migration review.

## Permanent URLs and playback authorization

Management returns permanent `playerUrl` and iframe markup based on `/watch/{publicId}`. The page and iframe contain no credential. The browser requests a short-lived signed HLS token from `/api/playback/{publicId}/token`; the player resolves the returned path-only media URL against the gateway/API origin (not the static player origin), renews before expiry, and adds the refreshed token to playlist/segment requests. Every proxy request validates HMAC, expiry, Broadcast enabled state and playback generation. Disable, delete, or visibility change invalidates previous tokens. Playlist references are rewritten through the proxy, so keys and segments are subject to the same authorization.

New Broadcasts default to `PRIVATE`. Anonymous token issuance is deliberately supported only for explicitly `PUBLIC` broadcasts; private playback returns 401. This MVP does not yet implement viewer accounts, signed invitations, password-protected broadcasts or per-viewer authorization. The management API can change visibility. Token URLs use query parameters for compatibility with native HLS implementations; the player and iframe set `no-referrer`, media responses are `no-store`, and operators must configure ingress/access logs to redact `token` query values. Never include full playback URLs in application logs, analytics, support tickets or telemetry. Tokens are opaque, short-lived, and contain no MediaMTX or RTSP credential.

The stream-service connects to MediaMTX with a read-only Basic credential. The ingest-agent publishes with a different credential. MediaMTX API, metrics and pprof are disabled. In streaming Compose, MediaMTX and the agent expose container ports only, not host ports. The gateway `/media-hls/**` compatibility route is still a direct proxy and is not the Broadcast player endpoint; keep the gateway loopback/private. Existing unauthenticated Camera/Stream APIs and WebSockets mean this deployment is not approved for public ingress. Streaming Compose sets the gateway CORS origin from `BROADCAST_PLAYER_ORIGIN`; keep it limited to the exact player origin.

MediaMTX 1.13.1 accepts indexed `MTX_AUTHINTERNALUSERS_<n>_*` environment values. The indexed list is reconstructed from environment rather than merged field-by-field with YAML, so every user's permission entries must be supplied with its username/password. Streaming Compose supplies a publish role with UUID-scoped `publish` and the narrow UUID-scoped `read` permission required by the ingest-agent's RTSP output readiness probe, plus a separate UUID-scoped reader for the HLS proxy. The isolated overlay adds a publisher restricted to the synthetic `test-source` path and an anonymous test-only reader restricted to that path. Positive and negative HTTP/RTSP requests verified these roles; never deploy the test-only users.

## API summary

The management endpoints are `/api/v1/broadcasts` through gateway rewriting and require `Authorization: Bearer $BROADCAST_MANAGEMENT_TOKEN`:

- `POST` with `{cameraId, visibility?}` creates or returns the existing Broadcast for that camera.
- `GET /{id}` and `GET` list details.
- `PUT /{id}/enabled` with `{enabled}` acquires/releases the Broadcast consumer lease.
- `PUT /{id}/visibility` with `{visibility: "PUBLIC"|"PRIVATE"}` rotates playback generation.
- `GET /{id}/playback` returns permanent watch and iframe URLs.
- `DELETE /{id}` revokes tokens, deletes the record and releases its lease.

Viewer endpoints are `GET /api/playback/{publicId}/token` and `GET /broadcast-play/{publicId}/{fileName}?token=...`. Management APIs are not intended for browsers without a trusted operator; use loopback/private ingress only.

## Security findings and limits

- Gateway legacy Camera, Stream, recording and websocket routes do not all authenticate users. Streaming Compose binds gateway/player to loopback by default. Do not widen the bind or add a public reverse proxy until gateway-level authentication and origin policy cover all routes. The isolated browser used the gateway's configured `http://localhost:5173` origin; `http://127.0.0.1:15173` was rejected by CORS.
- The legacy `/media-hls/**` route has no Broadcast token check. MediaMTX read auth remains enabled in streaming Compose, but gateway proxy auth is not configured there. Since the route is exposed only through private gateway ingress, do not publish it; a future public gateway must remove or protect it.
- Rust agent pipeline management uses Bearer auth; health/readiness and metrics are unauthenticated but internal-only. Do not publish agent ports.
- MediaMTX RTSP/HLS ports are not published. Publish and read credentials are distinct. RTSP source URLs/passwords remain server-side; StreamSession omits RTSP URL from JSON.
- HLS references are limited to safe filenames, and proxy paths are constructed from parsed UUIDs. Invalid paths/references fail closed. HLS requests are capped at 32 concurrent upstream calls and rate-limited in-process; these limits are not distributed.
- Management bearer authentication is a shared operator secret, not user identity. Secrets must be >=32 characters and stable across replicas/restarts. Do not use demo defaults outside local development.
- Playback token and MediaMTX password scans found no credential values in checked isolated container logs, and Chromium sent no token-bearing Referer. This only covers the containers checked here; configure access-log redaction at any future ingress. No public deployment is authorized by this phase.

## Recovery state

Broadcast `enabled` is desired state. StreamManager sessions and FFmpeg workers are actual runtime state. Enabled Broadcast leases are rebuilt from the database after service restart. Agent-managed workers poll/reconcile process and HLS health; transient camera/network or MediaMTX failures enter reconnect behavior. Camera auto-start has a persisted consumer lease and is reconciled when camera events arrive. PostgreSQL outages make lease acquire/reconcile fail closed; the scheduled reconciler retries. No durable actual-state heartbeat, distributed lock, automatic DB failover, or guaranteed recovery of a missed camera event is provided. The isolated stream-service, agent, MediaMTX and PostgreSQL restart scenarios passed before the host reboot. Post-reboot runtime recovery is unverified; the isolated test DB was stopped and camera-service was restarting when inspected.

## Deployment and test pointers

Use [deployment-modes.md](deployment-modes.md) to run surveillance and streaming independently. Use [broadcast-integration-tests.md](broadcast-integration-tests.md) for isolated Compose, MediaMTX authentication, API/browser tests, restart recovery, bounded load checks and measured results. Compose validation and unit tests alone do not prove media playback.

## Phase 3 prerequisites

Before commercial SaaS or public ingress: tenant-scoped identity/authorization and data migrations; private playback invitations or viewer auth; tenant-scoped Camera/Stream/Broadcast queries; origin policy; safe ingress and log redaction; distributed limits and ownership/leader locking; durable recovery status/alerting; per-tenant MediaMTX path credentials; retention, audit, quotas, billing and abuse controls; load testing across target viewer counts; backup/restore and privacy/deletion flows. No Kubernetes, CDN or billing service is needed to complete this MVP.
