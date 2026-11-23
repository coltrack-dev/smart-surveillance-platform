# Phase 3 — Roadmap after architecture review

## Phase 3.2A result — OIDC foundation

Implemented: pinned Keycloak development service isolated to Streaming; realm import with public SPA client and PKCE S256; dedicated Keycloak database/volume; dedicated Streaming PostgreSQL database/volume; provider-neutral security interfaces and initial User/Tenant/Membership/Role schema; authenticated identity discovery and membership lookup endpoints; issuer/JWKS/audience JWT validation in gateway, stream-service and identity-service; authenticated Streaming management routes; Vue Keycloak login guard for protected routes; preserved Surveillance legacy security mode. The migration is additive and has not been run against any existing database. See `IDENTITY_ARCHITECTURE.md` and `SECURITY_BOUNDARIES.md`.

Not complete: no tenant provisioning/onboarding API; no role-based authorization in Camera/Stream/Broadcast domain operations; no Camera/Broadcast/Recording/Analytics owner fields or backfill; no OIDC auth on direct internal Camera/WebSocket/agent paths; no authenticated WebSocket support; no private-member playback authorization; no browser login or live Keycloak integration run. Streaming remains loopback-only and is not approved for public ingress.

## Phase 3.2B — Tenant-scoped authorization (next)

- Add reviewed tenant provisioning/invitation and membership lifecycle operations, with audit and OWNER/ADMIN controls.
- Add tenant context resolution at the gateway/application boundary from validated issuer+subject and an active membership; never trust a selected tenant ID alone.
- Implement tenant-scoped Camera, Stream and Broadcast repositories and object-level permission checks, including internal Camera connection lookup.
- Add resource owner columns through reviewed additive migrations and operator-approved backfill; keep unowned legacy rows inaccessible to SaaS.
- Wire role evaluator to management permissions and add cross-tenant IDOR tests.
- Define internal service identity and secure Camera/WebSocket/agent endpoints; keep browser JWT away from ingest-agent.
- Gate: isolated DB migration, membership authorization, cross-tenant denial, all existing Surveillance behavior preserved.

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
