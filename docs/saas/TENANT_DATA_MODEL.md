# Phase 3.1 — Tenant data model and isolation

## Phase 3.2A implementation note

The isolated Streaming database now has the identity schema described below and `common-security` defines User, Tenant, TenantMembership, Role and authorization interfaces. This does not change Camera, Broadcast, Stream lease, Recording or Analytics schemas; those records still have no tenant owner. The identity migration is implemented but was not executed during this phase. See `TENANT_MIGRATION_PLAN.md` and `PHASE_3_2A_VALIDATION.md`.

## Current domain

Camera is the camera-service authoritative model: UUID, globally unique camera_number, name, RTSP address/username/encrypted password, processing mode, status, autoStart, heartbeat, category and optional LBS. It has no owner tenant. Camera JSON ignores credential fields, but the API has no authorization.

Stream is runtime state keyed by Camera UUID. StreamManager sessions and durable stream_consumer_leases coordinate shared ingest; Stream must not become a duplicate camera model. Broadcast is a stream-service JDBC row with camera_id, public_id, visibility, enabled, playback_version and timestamps. Current Broadcast migrations add no camera FK or tenant owner. Recording and Analytics rows refer to camera/resource IDs without tenant ownership. Each service has its own Flyway history; services may point to one PostgreSQL database.

## Ownership invariant

Each SaaS Camera has exactly one immutable tenant owner. Stream inherits tenant through Camera. Broadcast must have the same tenant as its Camera. Ownership transfer is an audited workflow, never a normal update. A missing/unassigned owner is inaccessible from tenant APIs. Do not infer ownership from a UUID, camera number, RTSP host or public ID.

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
