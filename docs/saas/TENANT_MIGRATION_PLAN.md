# Phase 3.2A — Identity foundation migration status

Identity-service now owns an additive Flyway migration at `services/identity-service/src/main/resources/db/migration/V1__identity_foundation.sql`. It creates only `identity.users`, `identity.tenants`, fixed `identity.roles`, and `identity.tenant_memberships`, with issuer+subject uniqueness, status/role constraints, foreign keys and membership lookup index. It runs in the isolated Streaming PostgreSQL database with history table `flyway_identity_schema_history`; Keycloak has a separate database and volume. Runtime and migration credentials are not yet separated, which must be corrected before a production deployment.

The Streaming Compose configuration no longer defaults Camera/Stream services to the old shared Surveillance database. Its new `streaming-db-data` volume is independent. This change does not copy or migrate existing rows; it does not alter any current volume. Do not point the new migration at Surveillance. Existing deployments must retain their previous database configuration and data.

No tenant ownership columns have been added to Camera, Broadcast, Stream leases, Recording or Analytics. No ownership backfill or migration is implemented or executed. A newly initialized Streaming identity schema has no tenants or memberships; the authenticated identity discovery API may create a user row, but cannot grant membership.

## Migration authority and sequence for subsequent phases

Identity-service is the initial authority for its schema only. Before cross-service ownership changes, choose a release-time migration authority/order and least-privilege roles. Preserve service ownership for local resource columns.

1. Back up the isolated target and prove restore; read-only inventory existing resources.
2. Add nullable tenant owner columns and tenant-aware indexes to Camera, then Broadcast/leases, followed by Recording/Analytics if enabled.
3. Prepare and review explicit owner mappings. No default tenant and no automatic backfill. Unknown rows remain inaccessible/quarantined.
4. Ship scoped repository/application checks compatible with nullable legacy rows; Streaming fails closed for unowned resources. Surveillance retains the legacy policy.
5. Backfill only operator-approved rows in audited resumable batches; then add composite constraints and staged NOT NULL where safe.
6. Evaluate PostgreSQL RLS only after scoped authorization and transaction-local context are tested with non-superuser runtime roles.

## Proposed ownership schema shape

## Status

Proposal only. No SQL/Flyway file was added or run and no database was accessed. Existing Camera and Broadcast ownership is unknown. No automatic backfill is allowed.

Services currently use separate Flyway history tables: camera-service flyway_camera_schema_history, stream-service flyway_stream_schema_history, recording-service and analytics-service own their histories. The deployments may share a PostgreSQL database, so service startup migrations can race or require tables that another service has not created.

## Migration authority and sequence

Before coding, choose one release-time authority for cross-service prerequisites (a migration bundle/release job or explicit operator sequence, not a new always-on business microservice). Retain service ownership for local columns but ensure a declared, tested order.

1. **Platform foundation:** create identity schema/tables for users, tenants and memberships with fixed roles, status constraints and indexes. Grant runtime roles least privilege; migration role is separate.
2. **Camera expansion:** add nullable tenant_id to cameras and tenant-aware indexes/unique support. Preserve existing camera_number semantics.
3. **Broadcast/lease expansion:** add nullable tenant_id to broadcasts and stream_consumer_leases; preserve current enabled leases, public IDs and playback versions.
4. **Recording/Analytics expansion:** add nullable tenant ownership to root rows and tenant-aware parent relations when these services are enabled for SaaS. Do not expose them meanwhile.
5. **Dual-compatible app release:** Surveillance continues legacy policy; Streaming requires a valid TenantContext and hides unowned data. Never fall back from scoped lookup to unscoped lookup.
6. **Reviewed backfill:** separate operator-approved mapping, dry-run report, idempotent batches and audit totals. Unknown rows remain quarantined.
7. **Constraint stage:** after reconciliation add composite FKs, tenant uniqueness and NOT NULL only for tables exclusively owned by SaaS. Add RLS only after transaction role/context design is tested.
8. **Kafka/media transition:** version events, add tenant metadata and remove RTSP URLs/secrets from messages before tenant validation is mandatory. Rotate any exposed shared credentials.
9. **Contract cleanup:** remove compatibility paths only in a separately reviewed release after telemetry confirms old clients are gone.

## Proposed schema shape

Illustrative specification, not executable migration. Reconcile actual schemas, grants and Flyway conventions before authoring.

~~~sql
CREATE SCHEMA IF NOT EXISTS identity;

CREATE TABLE identity.users (
  id UUID PRIMARY KEY,
  issuer TEXT NOT NULL,
  subject TEXT NOT NULL,
  email_normalized TEXT,
  display_name TEXT,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  UNIQUE (issuer, subject)
);

CREATE TABLE identity.tenants (
  id UUID PRIMARY KEY,
  slug TEXT NOT NULL UNIQUE,
  display_name TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('ACTIVE','SUSPENDED','DELETING')),
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  deleted_at TIMESTAMPTZ
);

CREATE TABLE identity.tenant_memberships (
  tenant_id UUID NOT NULL REFERENCES identity.tenants(id),
  user_id UUID NOT NULL REFERENCES identity.users(id),
  role_code TEXT NOT NULL CHECK (role_code IN ('OWNER','ADMIN','OPERATOR','VIEWER')),
  status TEXT NOT NULL CHECK (status IN ('INVITED','ACTIVE','SUSPENDED','REVOKED')),
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id, user_id)
);

ALTER TABLE cameras ADD COLUMN tenant_id UUID;
ALTER TABLE broadcasts ADD COLUMN tenant_id UUID;
ALTER TABLE stream_consumer_leases ADD COLUMN tenant_id UUID;
~~~

PostgreSQL CREATE INDEX CONCURRENTLY cannot run inside a normal transaction; coordinate Flyway transactional settings or use maintenance-window indexes. The identity schema name is provisional. Prefer a consistent migration authority over schema naming.

Recording/Analytics need nullable tenant roots and tenant-validated children. Object storage key prefix changes must be separately designed; never rewrite keys during this ownership migration.

## Backfill and compatibility procedure

1. Read-only inventory counts of cameras, broadcasts, leases, recordings, analytics jobs/events; identify missing parents, duplicates and orphan rows.
2. Verify target database/schema/role and backup restore in an isolated copy. Do not point migration at Surveillance production by default.
3. Build ownership map only from authoritative business records. Store resource type, UUID, tenant UUID, evidence, approver and mapping version outside source control.
4. Dry-run validator reports unknowns, duplicate assignments and cross-parent mismatch.
5. Apply approved rows in resumable batches; record actor/time/source and pre/post counts. Idempotency key is resource type + resource UUID + target tenant.
6. Keep unresolved rows null and unavailable through SaaS endpoints. Do not map them to the first tenant.
7. Verify constraints, scoped query behavior, backups and cross-tenant tests before enforcing not-null/RLS.
8. Retain explicit export/delete and retention procedures before commercial onboarding.

## Backup, rollback and failure handling

Take a PostgreSQL backup and prove restore before migration windows. Use expand/contract and additive changes first. Do not combine first login with destructive ownership migration. After tenant writes begin, dropping/reverting columns is not automatically safe; prefer forward repair or restore/reconcile from a verified backup. Keep Flyway histories intact; never baseline/repair to mask missing migrations. A partial failure means streaming remains off public ingress until reconciled. Never execute this plan automatically in Surveillance mode.
