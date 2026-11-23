# Phase 3 — Roadmap after architecture review

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

## Phase 3.2 — Identity and API protection (remaining work)

- Review production Keycloak operation, hostname/TLS, client origins, access-token lifetime, signing-key rotation and backup; development `start-dev` config is not production-ready.
- Add membership lifecycle, tenant authorization and audit; current identity endpoints only discover users and list/resolve existing active memberships.
- Replace Broadcast's global-secret bypass in OIDC mode with tenant-aware user/member checks; currently gateway authentication alone is in place and no domain tenant check exists.
- Protect internal Camera connection and WebSocket CONNECT/SUBSCRIBE with workload identity and tenant filtering; no public websocket route is allowed meanwhile.
- Decide a reviewed, isolated migration/bootstrap approach for first tenant/member. Current new schema is empty and no membership can be granted through API.
- Gate: 401 without identity, 403 insufficient role, direct-service bypass denied.

## Phase 3.3 — Schema, ownership and scoped queries

- Establish one release-time migration authority and verified backup/restore.
- Add identity tables and nullable tenant ownership fields with additive migrations.
- Read-only inventory; prepare reviewed mapping and quarantine unknown rows.
- Scope list/get/mutations, schedulers and child resources. Enforce tenant on parent-child relations.
- Consider RLS only after transaction-local context, restricted runtime DB roles and pool-reset tests.
- Gate: Camera and Broadcast cross-tenant IDOR tests and untouched legacy Surveillance compatibility.

## Phase 3.4 — Async and media isolation

- Version Kafka events with tenant/event/actor context; remove RTSP URL/secrets from messages and audit retention.
- Consumers validate event tenant against owned resource; retries, DLQ, schedulers and cleanup preserve context.
- Keep shared StreamManager leases and one ingest pipeline per Camera; do not duplicate domain models.
- Use tenant/camera media namespace with explicit compatibility mapping; enforce per-path MediaMTX authorization and rotate shared credentials.
- Make agent API token mandatory in Streaming mode.
- Gate: cross-tenant media path denial, no credential leaks, restart/lease recovery and one FFmpeg per source.

## Phase 3.5 — Playback and WebSocket authorization

- Define explicit public-consent and private member/invitation rules.
- Bind short-lived grants to tenant, Broadcast, media audience and revocation generation. Recheck on renewal and every playlist/segment/key request.
- Remove direct /media-hls from public ingress.
- Filter WebSocket delivery by tenant and membership.
- Add distributed rate limits, audit events and log redaction.
- Gate: anonymous private denial, explicit public playback, expired/tampered/revoked denial and membership revocation.

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
