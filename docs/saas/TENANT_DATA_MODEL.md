# Phase 3.1 — Tenant data model and isolation

**Database target:** Streaming SaaS uses PostgreSQL 16.13 (`postgres:16.13-alpine`). All new tenant and identity Flyway migrations must remain compatible with PostgreSQL 16. The identity V1/V2 migrations and tenant/OWNER/invitation acceptance were verified against isolated PostgreSQL 16.13; previous PostgreSQL 17 runs are historical test evidence, not a version requirement.

## Phase 3.2B implementation status (2026-11-23)

Camera, Broadcast and stream-consumer lease now have nullable `tenant_id` columns in additive service-owned migrations. Camera CRUD and check-stream operations in Streaming OIDC mode resolve membership and role from validated issuer+subject plus the selected tenant, then use tenant-qualified repository lookup. Broadcast ownership is derived from the authorized Camera and cannot be supplied by the browser. Stream management resolves the Camera through the signed service-to-service tenant delegation before changing a tenant lease. A missing tenant owner is never found by these scoped lookups; there is no default tenant or automatic backfill.

The migration files are `camera-service V2` and `stream-service V3`. They have **not** been applied to any database. Their columns remain nullable and there are no cross-schema foreign keys yet. Surveillance continues through the legacy unscoped code path in its trusted deployment. Stream sessions remain one per globally unique Camera ID; leases are tenant-qualified and stopping one consumer leaves other leases intact.

Internal Stream-to-Camera requests use a short-lived HS256 workload JWT with fixed issuer/audience and a signed `tenant_id` claim. Camera ignores request tenant headers on `/internal/cameras/**` and scopes metadata/connection reads by the verified claim. Browser JWTs are not sent to Rust ingest-agent. Camera deletion events include tenant context; Streaming ignores legacy unscoped delete events rather than performing global cleanup. The stream management response omits internal HLS URL and error diagnostics in OIDC mode.

**Not yet complete:** tenant provisioning and membership administration, a complete HTTP/controller authorization matrix (especially Camera update/delete/check/list cases), WebSocket tenant authorization/fanout, tenant context on Stream status events, full Kafka event credential cleanup, Recording/Analytics ownership, DB role separation/RLS, owner-approved backfill, and production public-ingress review. LBS/category references are not tenant-owned in this phase. Streaming stays private.

## Phase 3.2A implementation note

At the end of Phase 3.2A, the isolated Streaming database had the identity schema described below, while Camera, Broadcast and Stream lease did not yet have owner columns. That historical status is superseded by the Phase 3.2B status above. The identity migration remains unexecuted; see `TENANT_MIGRATION_PLAN.md` and `PHASE_3_2A_VALIDATION.md`.

## Current domain

Camera is the camera-service authoritative model: UUID, globally unique camera_number, name, RTSP address/username/encrypted password, processing mode, status, autoStart, heartbeat, category and optional LBS. Its owner is nullable during compatibility rollout. In Streaming, only a selected active membership can access a matching non-null `tenant_id`; Surveillance retains its trusted legacy API.

Stream is runtime state keyed by Camera UUID. StreamManager sessions and durable stream_consumer_leases coordinate shared ingest; Stream must not become a duplicate camera model. Broadcast is a stream-service JDBC row with camera_id, public_id, visibility, enabled, playback_version and timestamps. Current Broadcast migrations add no camera FK or tenant owner. Recording and Analytics rows refer to camera/resource IDs without tenant ownership. Each service has its own Flyway history; services may point to one PostgreSQL database.

## Ownership invariant

Each assigned SaaS Camera has exactly one immutable tenant owner. Stream inherits tenant through Camera. Broadcast must have the same tenant as its Camera. Current owner columns are nullable for legacy compatibility; no ownership transfer or backfill workflow exists yet. A missing/unassigned owner is inaccessible from tenant APIs. Do not infer ownership from a UUID, camera number, RTSP host or public ID.

Recording sessions, objects, analytics jobs/events, exports and cleanup tasks inherit ownership from their Camera or parent. Child records must not be queried without tenant-qualified parent scope. Shared physical source across tenants is unsupported until an explicit sharing model exists.

## Context propagation

HTTP requests: validate OIDC token, map issuer+subject to User, resolve active TenantMembership, authorize role, build immutable TenantContext, then call application services and tenant-qualified repositories. A header such as X-Tenant-ID may select a tenant but cannot establish authority.

Async and scheduled work: persist tenant UUID and actor/service identity in job/outbox/Kafka payloads. Include event ID, schema version and resource ID. Consumers verify event tenant matches resource ownership before writes. Retries, DLQs, schedulers, recording cleanup and stream recovery preserve and revalidate tenant context. Do not depend on ThreadLocal/MDC propagation across executors.

Workload calls: authenticate service identity and include validated tenant context. Camera internal connection lookup checks both workload identity and camera tenant. Do not place RTSP credentials in Kafka messages.

WebSockets: authenticate CONNECT, authorize each SUBSCRIBE destination, and either use tenant-specific topics or server-filtered user queues. Never broadcast global topics to all tenants.

## Proposed constraints and indexes

Identity tables:
- users: UUID PK; issuer and subject NOT NULL with unique pair; optional normalized email/display metadata; enabled and timestamps.
- tenants: UUID PK; unique slug; display name, status, timestamps, deleted_at.
- tenant_memberships: tenant_id and user_id FKs, fixed role_code CHECK, membership status, timestamps, PK(tenant_id,user_id); indexes (user_id,status,tenant_id) and (tenant_id,status,role_code).

Tenant resources:
- Add nullable tenant_id to cameras first. Add UNIQUE(tenant_id,id), index (tenant_id,camera_number), and tenant-leading operational query indexes. Preserve current camera_number uniqueness/sequence until compatibility is proven; do not renumber.
- Add nullable tenant_id to broadcasts. Add unique (tenant_id,camera_id), list index (tenant_id,enabled,updated_at), preserve globally unique public_id. After verified backfill, composite FK (tenant_id,camera_id) to cameras(tenant_id,id).
- Add tenant_id to stream_consumer_leases; tenant/resource/consumer uniqueness and indexes. Keep one pipeline for one physical source.
- Add tenant scope to recording and analytics roots; enforce child-parent tenant equality through composite foreign keys or validated application writes.
- Include tenant-leading indexes for all list, scheduler and cleanup queries. Mutation SQL must constrain by tenant_id and resource id, not fetch an arbitrary UUID and check later.

All repository find/list/update/delete APIs in Streaming mode require tenant context. An unauthorized guessed ID should have the same non-disclosing response as an unknown ID.

## PostgreSQL RLS as defense in depth

RLS can enforce tenant filtering beneath application checks, but it does not implement roles or prove membership. A policy may compare tenant_id with transaction-local app.tenant_id. Set it with SET LOCAL only inside a transaction after membership validation; never use a persistent pooled-session setting. Runtime DB roles must not be superusers, table owners bypassing RLS or have BYPASSRLS. Evaluate FORCE ROW LEVEL SECURITY and separate migration/maintenance roles. Missing context must return no rows/fail closed. Test connection-pool reuse, rollback, context switch, async work and maintenance tasks. Shared DB roles across services weaken isolation.

## MediaMTX namespace and credential handling

An internal namespace can use tenant/{tenant UUID}/camera/{camera UUID}; do not use customer names, RTSP credentials or bearer tokens in paths. Path naming alone is not authorization. Current Streaming roles have UUID-wide regex permissions and shared credentials, so they do not establish tenant isolation.

Keep listeners private. For SaaS use per-path authorization or short-lived/path-scoped publisher and reader credentials; separate ingest publish, readiness probe and playback proxy roles. The browser never receives MediaMTX credentials. Map current UUID paths through an explicit compatibility layer and controlled restart; do not silently rename demo paths. Rust agent credentials must be mandatory in Streaming configuration.

## Existing data with no tenant

Current Camera/Broadcast rows have no tenant_id. There must be no automatic default tenant or bulk backfill. First inventory rows and parent integrity read-only, take and restore-test a backup, prepare a reviewed resource-to-tenant mapping, and leave unknown rows quarantined/inaccessible. Backfill only approved rows in a resumable audited operation. Add NOT NULL and composite FKs only after every SaaS-visible row is assigned or an explicit legacy partition exists. Keep Surveillance on its isolated existing DB/schema and preserve all rows.

## Organization-management additions (Phase 3.3A)

Identity V2 adds `identity.tenant_invitations`; it does not alter Camera, Broadcast, Recording or ownership columns. Invitation token hashes are unique 32-byte SHA-256 values, with a partial unique pending invitation per tenant/email and an expiry index. Roles use the existing fixed `identity.roles` table; OWNER may invite any fixed role, while ADMIN is restricted to OPERATOR/VIEWER by application policy. Active membership remains unique by the existing `(tenant_id,user_id)` primary key. New indexes support tenant/status/role member administration.

The V2 deferred constraint triggers enforce at commit that each persisted tenant has at least one ACTIVE OWNER. They allow tenant and initial OWNER membership to be inserted in a single transaction. Organization mutations lock the tenant row before membership rows, providing a stable serialization point for application role changes. Flyway V1/V2 and their PL/pgSQL triggers passed isolated PostgreSQL 16.13 migration and concurrency acceptance. Do not run them against Surveillance or production databases as part of local testing.
