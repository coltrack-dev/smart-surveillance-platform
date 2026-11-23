# Phase 3 — Roadmap after architecture review

## Phase 3.2D — Multi-tenant security acceptance (2026-11-24)

**Status: BLOCKED for phase completion and public ingress.** In an isolated two-tenant setup, the controller IDOR matrix passed; live Gateway-routed STOMP/Kafka checks passed, including anonymous rejection, tenant destination enforcement, persisted Camera owner routing despite a forged event tenant claim, redaction and active membership revocation. Live HLS playlists/segments passed PUBLIC/PRIVATE, cross-tenant, expiry, tampering, revocation, disable/delete and direct `/media-hls` denial checks. Fresh and legacy Flyway startup plus pre/post ownership backup/restore passed. No Surveillance containers or data were targeted.

Phase 3.2D.1 closed the missing encrypted-key retrieval evidence using an isolated AES-128 HLS source: Gateway playback grant, playlist and authorized 16-byte key returned 200; missing, forged, expired, cross-tenant, revoked, disabled and deleted access were rejected. The separate hostile external key-URI check remains **BLOCKED**: its control playlist returned 200, but the modified playlist request returned 401 (with zero arbitrary upstream requests), rather than a conclusive rewrite rejection. Browser decryption was NOT TESTED. No prior IDOR, Kafka/STOMP, Flyway, backup/restore, browser or load tests were repeated. Keep Streaming loopback/private.

| Acceptance area | Current status | Next action |
|---|---|---|
| Camera/Stream/Broadcast route authorization, membership and unassigned rows | PASS (focused controller methods) | Before rollout, add full HTTP binding tests where request conversion/exception mapping is part of the risk. |
| Shared stream consumer lease acquire/release | PASS (tenant-bound controller delegation plus historical manager tests) | No separate viewer lease API exists; preserve one StreamManager session per Camera. |
| Gateway/STOMP/Kafka tenant isolation and revocation | PASS (live isolated) | Reuse prior Surveillance destination unit evidence; keep WebSocket service private. |
| Protected encrypted HLS authorization | PASS for playlist and exact key retrieval; hostile external URI guard BLOCKED | Diagnose the modified-playlist 401 and capture an authorized rejection (expected 502) with zero arbitrary upstream requests; browser AES-128 decode remains untested. |
| Flyway lifecycle and synthetic backup/restore | PASS (isolated) | Rehearse against supported schema snapshots, measure locks, and establish release migration/restore procedure before production. |
| Public ingress | NOT APPROVED | Independent security review, encrypted-key decision, TLS/host policy, rate limits and route allowlist remain prerequisites. |

Phase 3.2D is not complete while the hostile external key-URI check and independent public-route review remain unresolved. The encrypted key retrieval gap itself is closed. No public ingress is approved.

## Phase 3.2A result — OIDC foundation (historical; Phase 3.2B status follows)

Implemented: pinned Keycloak development service isolated to Streaming; realm import with public SPA client and PKCE S256; dedicated Keycloak database/volume; dedicated Streaming PostgreSQL database/volume; provider-neutral security interfaces and initial User/Tenant/Membership/Role schema; authenticated identity discovery and membership lookup endpoints; issuer/JWKS/audience JWT validation in gateway, stream-service and identity-service; authenticated Streaming management routes; Vue Keycloak login guard for protected routes; preserved Surveillance legacy security mode. The migration is additive and has not been run against any existing database. See `IDENTITY_ARCHITECTURE.md` and `SECURITY_BOUNDARIES.md`.

At the Phase 3.2A checkpoint these items were incomplete: tenant provisioning/onboarding, resource authorization/ownership, internal service identity, WebSocket authorization and private-member playback. Phase 3.2B progress and remaining gaps are listed below. Streaming remains loopback-only and is not approved for public ingress.

## Phase 3.2B — Tenant ownership and scoped APIs (implemented, validation incomplete)

Implemented in source: nullable owner columns via Camera V2 and Stream V3 additive Flyway migrations; tenant-scoped Camera CRUD/check/heartbeat; role-aware Camera, Stream and Broadcast API checks; Broadcast owner inherited from Camera; tenant-aware Stream leases and service-owned recovery; signed tenant workload JWT for Camera internal reads; Stream membership recheck for private playback; tenant-scoped Camera deletion event cleanup; legacy Surveillance paths retained. No migration has been executed and no existing row has been assigned a tenant.

Focused tests cover the role matrix, workload JWT validation/header forgery, Camera ownership service, Camera internal workload auth, Stream stop authorization/cross-tenant lookup, Broadcast tenant creation/lookup, private playback membership revocation and tenant-scoped Camera deletion cleanup. See the Phase 3.2B appendix in `docs/TEST_INVENTORY.md` for exact methods/results. No browser E2E, Docker, load, database migration or public-ingress test was run.

Before Phase 3.2C: complete Camera controller tests for list/update/delete/check/start; test anonymous PRIVATE playback and HTTP expiry/tamper/revocation; add Broadcast controller role/IDOR tests; add restart/lease recovery tests against an isolated migrated DB; add tenant context to Stream status Kafka events and WebSocket delivery; verify all OIDC configurations fail closed; decide LBS/category ownership; approve staged migration/backfill and DB grants. Keep Streaming private until these gates pass.

## Phase 3.1 result

Architecture and migration documentation only. No source code, Compose files, schemas, databases or tests were modified or run. The existing test inventory is 78 Java methods: 56 recorded PASSED and 22 NOT RUN; Python integration, Playwright and load evidence are listed separately in docs/TEST_INVENTORY.md. Do not rerun the 56 passed methods solely for confirmation.

## Dependencies

Vue/browser -> camera-gateway -> camera-service, stream-service, websocket-service.
stream-service -> internal Camera connection API -> Rust ingest-agent -> MediaMTX -> protected HLS.
Camera/Stream/Recording/Analytics services -> PostgreSQL.
Camera/Stream -> Kafka -> consumers/workers/WebSocket service.

Identity precedes tenant-scoped API authorization. Ownership columns precede scoped repositories. Tenant-aware Kafka events precede async tenant workers and WebSocket fanout. Playback policies depend on Broadcast ownership and membership. Recording/Analytics require explicit SaaS rollout gates. Media path isolation depends on trusted tenant-to-camera mapping.

## Phase 3.2 — Identity, ownership and cross-transport authorization

Implemented: Keycloak/OIDC JWT validation; application-owned memberships and role checks; nullable tenant ownership for Camera/Broadcast/Stream consumer leases; scoped management operations; workload-authenticated internal Camera lookup; tenant-bound stream leases; persisted-owner Kafka routing; STOMP tenant subscriptions; short-lived Broadcast playback grants and protected HLS proxy. Isolated service Flyway startup, legacy unassigned rows, two-tenant Gateway/STOMP/Kafka behavior and synthetic backup/restore have been exercised.

Remaining before Phase 3.2 acceptance or any public ingress:

- Resolve the live encrypted HLS key scenario if encrypted playlists are enabled; the current synthetic HLS source had no key URI.
- Add full HTTP binding/controller-slice assertions for the management IDOR matrix where serialization and framework exception mapping matter; current new negative tests call controller methods directly.
- Add tenant provisioning, membership lifecycle administration and audit trails; the identity API discovers and resolves memberships but does not provision them.
- Separate runtime and migration database credentials; define supported schema-upgrade order, lock/latency limits, restore rehearsal and a post-assignment forward-repair procedure.
- Review Keycloak production hostname/TLS, browser origins, token/key rotation, session revocation and backups; development `start-dev` config is not production-ready.
- Keep Recording, Analytics, search and admin/debug surfaces unavailable to SaaS until ownership and authorization cover their full data lifecycle.
- Gate: encrypted key access when applicable, independent security review, route/port allowlist, TLS and explicit release authorization.

## Phase 3.3 — Remaining tenant data ownership

- Extend ownership to Recording, Analytics, search and other enabled services; keep them private until then.
- Prepare a read-only inventory and reviewed mapping for legacy rows; do not automatically assign a default tenant.
- Scope child resources, schedulers, exports, object storage and cleanup; enforce tenant on parent-child relations.
- Consider RLS only after transaction-local context, restricted runtime DB roles and pool-reset tests.
- Gate: cross-tenant tests for every enabled resource and untouched legacy Surveillance behavior.

## Phase 3.4 — Async and media isolation follow-up

- Add schema versioning, actor/audit context, retries and DLQ policy; current tested listeners resolve Camera owner from persisted state and redact source media/error details.
- Keep shared StreamManager leases and one ingest pipeline per Camera; do not duplicate domain models.
- Define tenant/camera MediaMTX path namespace and compatibility mapping; rotate shared media credentials and add a tenant-aware workload identity lifecycle.
- Make agent API token mandatory in Streaming mode.
- Gate: path isolation, credential rotation, restart/lease recovery and one FFmpeg per source.

## Phase 3.5 — Playback and WebSocket production hardening

- Preserve explicit PUBLIC consent and PRIVATE membership policy; tested token issuance and HLS media fetch recheck current grant/membership.
- Verify encrypted key fetch live when enabled; ensure all reverse proxies redact token query parameters.
- Add distributed rate limits, audit events and operational log redaction beyond the current in-process limits.
- Keep direct `/media-hls/**` private; allow only reviewed public Broadcast token/media routes behind TLS.
- Gate: completed key-path review, renewal/revocation telemetry, abuse controls and public-route security review.

## Phase 3.6 — Recording and Analytics

Only enable these services for SaaS after tenant ownership covers parent/child rows, object storage, background cleanup, export and deletion. Otherwise keep their APIs private/inaccessible in Streaming.

## Phase 3.7 — Production readiness

TLS and cookie/domain policy; secrets/key rotation; rate/abuse limits; audit retention; quotas; tenant suspension, deletion/export; backup/restore; observability, SLOs and incident response. Public ingress is an explicit release gate.

## New focused test plan

- Identity: issuer/audience/signature, expired/not-before/wrong-audience, unknown key, user mapping by issuer+subject, disabled users, rotation and revocation.
- Membership: user in multiple tenants, invalid tenant selector, every role, suspended/revoked membership, permission-change propagation.
- Camera IDOR: tenant A cannot list/get/update/delete/check/start tenant B camera; compare response with unknown UUID.
- Broadcast IDOR: create only for owned Camera; get/list/enable/disable/delete/playback details remain tenant scoped; parent tenant mismatch denied.
- Playback: PUBLIC only after explicit consent; PRIVATE anonymous denied; membership/invite access; token scope, audience, expiry, renewal and revocation on disable/delete/privacy or membership change.
- WebSocket: unauthenticated CONNECT, cross-tenant SUBSCRIBE, origin, one-use browser ticket, membership revocation and no cross-tenant event fanout.
- Async: missing/forged tenant, Kafka schema version, resource mismatch, retries/DLQ/scheduler context, executor context leakage.
- Media: malformed path, traversal, tenant A publisher/read denied for tenant B, missing agent token fails Streaming startup, no RTSP credential in event/log/browser.
- Database: composite foreign keys, constraints, RLS absent-context and pooled connection tenant switch.
- Surveillance: isolated legacy fixture verifies current camera CRUD, auto-start, Stream leases, recording, analytics and demo routes. Do not touch production volumes/data.

These are future tests, not executed in Phase 3.1. Keep Java methods, parameterized invocations and assertions as separate counts in reports.

## Pilot exit criteria

Every management/internal route authenticates; every tenant resource operation checks owner/membership; unknown data is quarantined; PUBLIC requires owner consent and PRIVATE denies anonymous; WebSocket and Kafka are tenant-aware; media credentials/path are scoped; no RTSP secret reaches Kafka/browser/logs; rate limits/audit/backup recovery are proven; Surveillance behavior and data remain unchanged; security review approves an exact public route/port allowlist.
# Phase 3.2C cross-transport gate (2026-11-23)

**Status: BLOCKED; Phase 3.2 is not complete and Streaming is not approved for public ingress.** Focused tests now cover tenant-bound STOMP sessions, membership revocation on outbound frames, persisted-owner Kafka routing, spoofed Camera deletion tenant claims, protected HLS proxy behavior with a mocked upstream, and unassigned legacy ownership rows on isolated PostgreSQL. Additive SQL application and manual reverse DDL rollback with all ownership values NULL passed in the disposable database. Legacy Surveillance unit compatibility and both Compose static configurations passed.

The remaining gate is evidence, not a feature expansion: controller-level IDOR coverage is incomplete for Camera update/delete/list/status, Stream start/read/list, Broadcast list/read/delete/playback-info and lease operations. Live STOMP/Kafka delivery, real MediaMTX HLS token expiry/revocation/cross-tenant denial, Flyway startup/history and rollback after ownership assignment are unverified. Do not expose Streaming publicly or claim Phase 3.2 completion until these checks pass in an isolated environment.

Next sequence:

1. Complete the remaining focused controller tests and run each affected Gradle `Test` task separately with exact filters.
2. Add a small isolated two-tenant Kafka + WebSocket integration scenario and verify active-session membership revocation.
3. Add isolated Gateway-to-MediaMTX HLS tests for PUBLIC/PRIVATE, tenant mismatch, expired/tampered grant, disable/delete, membership revocation, segments and keys.
4. Validate Flyway startup/history and restore behavior against disposable databases seeded from supported legacy schema snapshots; only manual pre-assignment reverse DDL has been verified. Do not run migrations on Surveillance/production databases.
5. Review all non-Streaming consumers and internal service credentials before enabling SaaS deployments; Recording, Analytics, search and admin/debug surfaces must remain private until tenant-aware.
6. Only after evidence matrix is all PASS, conduct a separate security review and explicit deployment approval.
