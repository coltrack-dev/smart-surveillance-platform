# Usage and quotas: Phase 3.3C

## Status

Implemented in Phase 3.3C.1–3.3C.3: an Identity-owned plan catalog, organization plan assignment and per-organization quota override schema; effective quota resolution; atomic Camera, active Broadcast and concurrent viewer admission; tenant-scoped usage reads and Dashboard indicators; and separate least-privilege PostgreSQL runtime/migration identities. Viewer sessions were validated on isolated PostgreSQL 16.13. Earlier Identity V1–V5, Camera V1–V5 and Stream V1–V4 migration evidence is recorded below.

Not implemented: plan/override administration, scheduled usage reconciliation, warnings, suspension, billing or payment processing. Camera, enabled Broadcast and active viewer counts are queried from their authoritative tables and survive restarts. Dashboard usage is informational; admission decisions remain server-side. Do not treat the illustrative plans as commercial commitments.

## Phase 3.3C.2B — Runtime acceptance (2026-11-25)

**Status: PASS; C.2B closed for the focused acceptance scope.** Previously completed quota runtime checks were retained as historical PASS and not repeated. The resumed least-privilege and live-media checks used an isolated `phase33c2b-lp-media` project, PostgreSQL 16.13, loopback-only ports, and synthetic tenants/Camera/Broadcast. The protected preview, `phase33b1`, demo and Surveillance resources were not modified.

The earlier C.2 implementation evidence remains valid: its 19 focused tests passed, including transaction-level quota races and migration preservation. The following runtime checks had already passed before interruption and were deliberately not repeated: multi-instance Camera and Broadcast HTTP last-slot concurrency, Camera same-payload idempotency and conflicting retry, cross-tenant quota isolation, quota-authority outage behavior, Dashboard quota indicators/error messages, and persisted Broadcast state/lease reconciliation. Newly completed checks:

| Scenario | Result | Evidence |
|---|---|---|
| Fresh database migrations and restricted-role startup | **PASS** | Identity V1–V5, Camera V1–V5 and Stream V1–V4 applied; Identity, Camera and Stream connected under separate runtime roles. WebSocket uses a separate function-only role. |
| Existing-schema synthetic upgrade | **PASS** | A clone with pre-security schema/history and shared old object ownership migrated to current versions. Existing row counts remained 1 tenant, 2 Cameras, 1 Broadcast; migrated history tables had no runtime grants. |
| Exact runtime/migration privilege checks | **PASS** | No role has SUPERUSER, CREATEDB, CREATEROLE, BYPASSRLS or runtime DDL. Runtime grants are limited to their table sets; Camera/Stream receive only scoped Identity SECURITY DEFINER helper EXECUTE. Only `identity_migrator` has database CREATE for legacy V1 `CREATE SCHEMA`; Camera/Stream migrators have CREATE on `public` for existing unqualified migrations. |
| Quota locks and tenant authority under restricted roles | **PASS** | Live `camera_runtime` and `stream_runtime` connections acquired PostgreSQL transaction advisory locks and read active-tenant/effective-quota helper results. Identity tables are not directly readable by resource runtime roles. |
| Runtime privilege boundaries | **PASS** | Runtime roles cannot create databases/roles or create schemas/tables; Camera cannot update Broadcast tables and Stream cannot update Camera tables. Identity runtime cannot write Camera/Broadcast tables. |
| RTSP source unavailable, then restored | **PASS** | Broadcast remained desired-enabled with exactly one lease while RTSP returned 404; agent retried without a PID. After one Stream restart and restoration of the synthetic FFmpeg source, the media session reached RUNNING and HLS/FFprobe returned playable H.264 320×180 video. |
| Retry/reconciliation lease accounting | **PASS** | Across retries and Stream restart the Broadcast/lease count stayed 1/1. Deactivation returned `enabled=false`, released the lease (0), removed the media session, and left no agent or test-source FFmpeg process. A subsequent available-source activation again reached playable HLS and deactivated cleanly. |

Synthetic acceptance rows and both isolated PostgreSQL volumes were preserved. Temporary application/media services were stopped after recording results; the PostgreSQL container was subsequently stopped without removing its volume. A previous harness had stale IDs/port mapping and UI navigation issues; these were corrected then and are historical harness issues, not current failures.

Phase 3.3C.2B **is closed** for the stated acceptance criteria. Not run: out-of-band plan/override coordination, query/index performance, full regression, public ingress, browser-wide acceptance, and load/stress/soak testing. These are not represented as passing.

## Phase 3.3C.2 — Implemented behavior

Camera Service owns Camera persistence. Streaming creation requires an `Idempotency-Key`; the key is tenant-scoped, while a keyed HMAC of the canonical request is stored to distinguish a retry from key reuse with changed request data. The Camera row and idempotency record are committed in the same transaction. The HMAC uses the existing 32-byte Camera credential key with a domain-separated derivation; neither the plaintext RTSP password nor raw key is stored in the idempotency table. Same-key/same-payload retries return the existing Camera without consuming quota again. Same key with changed details, or a key whose Camera was deleted, returns 409.

Stream Service owns Broadcast persistence. Only a `false → true` transition consumes `MAX_ACTIVE_BROADCASTS`. An already-enabled retry is idempotent and does not reserve another slot. Creating a disabled Broadcast does not consume a slot. Disable and delete remain allowed when usage is at or above a lowered limit. Enabled/offline Broadcasts count because `enabled` is persisted desired state; StreamManager lease reconciliation restores media state separately.

Both resource services use one shared `TenantQuotaGuard` against the existing Streaming PostgreSQL database. In each admission/release transaction it obtains `pg_advisory_xact_lock(hashtextextended(tenant UUID, 0))`, verifies the tenant remains active, resolves the plan/overrides from restricted Identity helper functions, counts the resource in its owner table, and applies the write before commit. The advisory transaction lock serializes all Camera/Broadcast quota transitions for the same organization across service instances without requiring UPDATE privileges on `identity.tenants`. A hash collision only serializes unrelated tenants; it cannot admit extra usage. Identity, Camera and Stream use separate runtime identities. A split-database deployment remains unsupported by this strategy and fails closed when quota helpers cannot be read.

No remote request is made while holding the quota lock. Camera registration Kafka publication happens after commit. Broadcast enabled state commits in a dedicated transactional lifecycle bean; StreamManager lease acquire/release follows after that bean returns, and the existing periodic desired-state reconciliation repairs media side effects after failures. The persisted count remains authoritative even if ingest is offline. Camera create rollback removes both row and idempotency record. Quota lowering does not mutate existing rows; only subsequent increases are refused.

Quota-exhaustion APIs return HTTP 409 with `CAMERA_QUOTA_EXCEEDED` or `ACTIVE_BROADCAST_QUOTA_EXCEEDED`, metric name, current usage and limit. Quota schema/database/configuration failures fail new admissions with HTTP 503 `QUOTA_AUTHORITY_UNAVAILABLE`; no fail-open fallback exists. Disable/delete are cleanup paths and do not need plan lookup, though they still require access to the owning resource database. Usage endpoints are `GET /api/v1/cameras/usage` and `GET /api/v1/broadcasts/usage` through Gateway, use the selected `X-Tenant-ID` only after membership authorization, and return `Cache-Control: no-store`. Each response includes `tenantId`, resource type, `used`, `limit`, and non-negative `remaining`. Camera usage counts existing Camera rows; Broadcast usage counts `enabled=TRUE` rows.

Camera Flyway V4 adds `camera_create_idempotency` without assigning tenant ownership or changing existing Camera rows. Identity V4 adds fixed-search-path SECURITY DEFINER helper functions for the specific active-tenant, effective-quota, membership and role lookups needed across service boundaries. Identity V5, Camera V5 and Stream V4 remove runtime access to their respective Flyway history tables; Identity V5 also removes direct Identity function execution from `identity_runtime`. Existing Surveillance requests with no tenant stay on the legacy path; the new migrations are additive and the quota guard is only called for tenant-owned Streaming operations. Existing Surveillance data and volumes were not contacted during acceptance.

## PostgreSQL roles and grants

`docker/streaming-db-roles.sql` is the repeatable bootstrap, run as the Streaming database administrator. It creates separate `identity_runtime`, `camera_runtime`, `stream_runtime`, `websocket_runtime` roles and `identity_migrator`, `camera_migrator`, `stream_migrator` identities. All seven are `NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT`; all receive only `CONNECT` on `streaming`. PUBLIC database TEMP/privileges and PUBLIC schema privileges are revoked. Runtime roles cannot create roles, databases, schemas, tables or sequences.

Application table grants are exact: `identity_runtime` has SELECT/INSERT/UPDATE/DELETE and sequence USAGE/SELECT only in `identity`; `camera_runtime` has the same DML only on `cameras`, `camera_categories`, `lbs_locations`, `camera_deletion_tombstones`, and `camera_create_idempotency`, plus USAGE/SELECT on `camera_number_seq`; `stream_runtime` has that DML only on `broadcasts` and `stream_consumer_leases`. The resource roles cannot select Identity tables directly. They receive EXECUTE only on `identity.is_active_tenant(uuid)` and `identity.effective_tenant_quota(uuid,text)` for quota admission, and `identity.resolve_tenant_membership(text,text,uuid)` plus `identity.resolve_active_role(uuid,uuid)` where membership/role lookup is needed. The WebSocket runtime role receives only schema USAGE and EXECUTE on the latter two helper functions, with no table DML. SECURITY DEFINER functions pin `search_path` to `pg_catalog, identity` and revoke PUBLIC execution.

Migration identities own their respective objects and Flyway histories. Their default privileges grant only their service runtime role DML on future tables and USAGE/SELECT on future sequences; Identity function execution is not granted by default to its runtime role or PUBLIC. Identity migrator requires database-level CREATE because existing Identity V1 executes `CREATE SCHEMA IF NOT EXISTS identity`; this is not CREATEDB and is not granted to application roles. Camera and Stream migrators receive CREATE only on `public` because legacy migrations use unqualified object creation. Runtime roles receive only schema USAGE. The bootstrap and migrations revoke service runtime grants on Flyway history tables.

## Ownership and current sources of truth

| Resource / metric | Authoritative owner | Current persistence and semantics | Foundation status |
|---|---|---|---|
| Organization, membership, plan, quota override | Identity Service | `identity.tenants`, memberships and the new quota tables | Plan/config authority implemented |
| Cameras | Camera Service | `cameras.tenant_id`; a camera counts while its row exists. Delete is physical after writing the separate deletion tombstone. | No usage aggregation or enforcement |
| Active broadcasts | Stream Service | `broadcasts.tenant_id` and `broadcasts.enabled`; enabled is desired/authoritative active state, independent of source health. | No usage aggregation or enforcement |
| Concurrent viewers | Stream Service | `public.viewer_sessions`; unrevoked rows with `expires_at > now()` count for the owning tenant. HLS requests do not create additional sessions. | Atomic tenant-scoped admission and usage endpoint implemented in Phase 3.3C.3 |

Streaming services currently connect to the same PostgreSQL 16 database, but own separate Flyway histories and tables. Identity must not query Camera/Stream tables directly as a substitute for service ownership. Any future usage snapshot should use explicit internal aggregate APIs or a transactional metering ledger with a documented reconciliation process. Do not add an Identity dependency to each HLS playlist/segment request.

## Plans and effective quota resolution

`identity.quota_plans` is the database-backed plan catalog. Migration V3 seeds illustrative `FREE`, `PRO` and `BUSINESS` plans:

| Plan | Cameras | Active broadcasts | Concurrent viewers |
|---|---:|---:|---:|
| FREE | 2 | 1 | 3 |
| PRO | 10 | 5 | 25 |
| BUSINESS | 50 | 25 | 200 |

These values are initial catalog defaults, not commercial promises. Plan codes and numeric limits are database-configurable; no public plan-edit API is exposed. `IDENTITY_QUOTAS_DEFAULT_PLAN` selects the plan for newly created organizations and defaults to `FREE`.

`identity.tenant_quota_assignments` stores an explicit plan assignment. Organization creation writes its default assignment in the same Identity transaction as the tenant and initial OWNER membership. Older organizations receive no data backfill: when no assignment exists, resolution uses the configured default plan. `identity.tenant_quota_overrides` stores optional per-metric overrides; only non-null fields replace the assigned plan's value. Effective limit is `COALESCE(override, plan limit)` independently for each metric.

The current API does not modify plan assignments or overrides. Those writes require a separately authorized administrative workflow, audit actor/reason, and concurrency policy before production use.

## API contract

Implemented through the existing Gateway organization route:

```http
GET /api/v1/organizations/{organizationId}/quotas
Authorization: Bearer <OIDC access token>
```

Gateway rewrites it to Identity Service `/api/organizations/{organizationId}/quotas`. The caller must have an active membership in the selected organization. Tenant ID in the path is only a selector; Identity validates the OIDC issuer/subject and persisted active membership. Unknown, suspended, and inaccessible organizations return the same 404 policy. Anonymous access returns 401. Responses use `Cache-Control: no-store`.

Example response:

```json
{
  "organizationId": "<organization UUID>",
  "planCode": "FREE",
  "planName": "Free",
  "limits": {
    "maxCameras": 2,
    "maxActiveBroadcasts": 1,
    "maxConcurrentViewers": 3
  },
  "overrides": {
    "maxCameras": null,
    "maxActiveBroadcasts": null,
    "maxConcurrentViewers": null
  }
}
```

Usage is deliberately not returned as zero or estimated. A future aggregate endpoint may use `GET /api/v1/organizations/{organizationId}/usage`, with `used`, `limit`, `remaining`, `asOf` and source/freshness metadata per metric. Until authoritative data sources are available, that endpoint is NOT IMPLEMENTED. Usage visibility should be limited to OWNER/ADMIN unless product policy decides otherwise; quota limits may be read by any active organization member.

## Viewer sessions and remaining enforcement design

The implemented same-database lock requires resource-service writers and any future plan/override mutation workflow to follow the same advisory-lock convention. Plan/override administration is not present; direct out-of-band quota edits must be coordinated with admissions until that workflow is added. Keep HLS authorization independent of Identity HTTP availability; viewer session enforcement must not add a synchronous Identity request per playlist, segment or key.

## Phase 3.3C.3 — Persistent viewer sessions

**Status: PASS; Phase 3.3C.3 acceptance is closed for the stated scope (2026-11-26).** Stream Service is authoritative for admission and usage. Flyway `stream-service` V5 creates `public.viewer_sessions`, owned by `stream_migrator`; `stream_runtime` receives only DML through Stream default privileges. Each row stores a UUID, tenant and Broadcast foreign key, optional PRIVATE user ID, a unique 32-byte SHA-256 credential hash, created/last-seen/expiry timestamps and nullable revocation time. Credentials are 256-bit random values, sent in the session API body and held in tab `sessionStorage`; they are never returned, logged, placed in permanent watch/iframe URLs, or stored raw. The Broadcast foreign key cascades on delete. Partial indexes support active tenant/Broadcast usage and bounded expiry/revocation cleanup.

The player creates a new credential for each normal tab navigation (so two tabs normally reserve two slots) and reuses it on same-tab browser reload. Browser restart or a new tab creates a new logical session; an abandoned old row stops counting after the 90-second default TTL. The player renews every 30 seconds. Each session grant contains the session UUID and is validated against the persisted active row on every HLS request; playlist, segment and key requests do not affect viewer count. Renewal with the same credential and matching tenant/Broadcast/user extends that row and reuses its UUID. An expired credential rotates to a new session UUID. Explicit release on page close is best effort; expiry still releases capacity when close delivery is lost.

Admission takes the existing PostgreSQL transaction advisory lock for the tenant, re-reads current Broadcast state and membership, resolves `MAX_CONCURRENT_VIEWERS` through `identity.effective_viewer_quota(uuid,text)`, counts `expires_at > now()` sessions, and inserts/renews in the same transaction. Simultaneous last-slot requests across Stream instances serialize; only one may use the remaining slot. Existing valid renewals remain idempotent at quota. Expired rows do not count before physical deletion. Identity V6 grants `stream_runtime` EXECUTE on only the fixed-search-path quota helper; it does not grant Identity table reads. The Stream migrator owns the table and future Stream default privileges grant the runtime role DML; no runtime DDL or Flyway-history access is added. Authority/database failures fail closed for admission and renewal. No network call occurs under the lock.

Cleanup runs every 60 seconds in batches of at most 250 rows using the expiry/revocation indexes and `FOR UPDATE SKIP LOCKED`; it takes no global cleanup lock. Disable, delete and visibility changes revoke Broadcast sessions transactionally with the lifecycle change. PRIVATE admission and every HLS request require current membership; the row/grant binds the user and tenant. PUBLIC supports anonymous admission while the random credential and tenant/Broadcast binding prevent bearer-ID guessing or cross-scope reuse. The Dashboard exposes only the selected member-authorized tenant's `GET /api/v1/broadcasts/viewer-usage` usage and returns no-store responses; viewer exhaustion is `429 VIEWER_QUOTA_EXCEEDED`.

The plan's `MAX_CONCURRENT_VIEWERS` values are now enforced. The 32-slot HTTP semaphore remains a separate upstream-fetch limit. Remaining risks: public anonymous users can churn random sessions until rate controls intervene; there is no distributed per-IP admission limiter or device/account-level viewer policy. IP is not used as viewer identity. The 90-second lease bounds abandoned-browser capacity but adds a delay before reclaim. Billing and public ingress remain out of scope.

### Phase 3.3C.3 acceptance evidence (2026-11-26)

On isolated PostgreSQL 16.13 and synthetic tenant/Broadcast data, two simultaneous last-slot HTTP admissions sent to different Stream instances returned one `200` and one `429`; persisted active usage ended at the configured limit of two. A same-credential renewal through the other instance returned the same session UUID and one active row. Independent credentials produced distinct IDs. PUBLIC session admission returned no credential; explicit release returned `204`. An expired row stopped counting immediately (`0 active / 1 expired`) and the scheduled bounded cleanup removed it. The earlier C.2B Camera/Broadcast concurrency, idempotency, tenant-isolation and outage scenarios were not repeated.

The short-TTL browser check played real synthetic HLS for 111 seconds across four unique playback grants, retaining one session UUID and receiving 109 successful HLS responses; the permanent watch URL contained no credential/token. Identity V6 and Stream V5 applied under the isolated restricted runtime/migrator identities on PostgreSQL 16.13. Focused Stream tests, including PUBLIC/PRIVATE binding, tenant/Broadcast credential scope, fail-closed quota behavior, lifecycle revocation and tenant-authorized no-store viewer usage, passed. Exact test totals and the unrelated failed historical attempt are in `../TEST_INVENTORY.md`. No protected preview/demo/Surveillance resources or volumes were modified.

## Migration and rollout

Identity Flyway V3 is additive and PostgreSQL 16 compatible. On 2026-11-25 it was applied by Flyway in sequence after V1/V2 to a fresh isolated PostgreSQL 16.13 database; a separate isolated database was first migrated to V2, seeded with a synthetic tenant/user/OWNER membership, and upgraded to V3. Both histories recorded successful SQL migrations V1, V2 and V3. The upgrade preserved its tenant and OWNER membership and created no plan assignment/override for that legacy tenant. The fresh migration seeded the expected catalog and constraints. No production, Surveillance, preview, or Phase 3.3B.1 database was used.

V3 adds the plan catalog, explicit assignment and nullable per-metric override tables. It does not alter Camera, Broadcast, playback, membership or existing ownership rows, and does not automatically assign existing tenants; resolution falls back to the configured default plan. The new organization assignment is written transactionally with organization creation. Acceptance also verified that an OWNER insert failure rolls back the tenant and quota assignment together, and that existing deferred OWNER constraints remain installed.

### Runtime-role migration and existing installations

Fresh Streaming deployments run the role bootstrap, initial schema/owner script and Flyway migrations in order. Flyway uses each dedicated migration identity, while service pools use their distinct runtime identity. For an existing installation, the tested procedure is a separate reviewed maintenance operation: take and verify a database backup; stop only that installation's application services; preflight that the seven role names are unused or already belong to this Streaming installation; execute `docker/streaming-db-roles.sql` as its database administrator with new independent secrets; execute `docker/streaming-existing-role-ownership.sql` to transfer existing objects; deploy the application/Flyway versions so V4/V5 Identity, V5 Camera and V4 Stream apply; start services on the new runtime credentials; then verify service health, migration history, exact grants, and tenant/resource row counts. The synthetic upgrade clone passed this sequence and preserved 1 tenant, 2 Cameras and 1 Broadcast. If any step fails, use the installation's reviewed backup/restore procedure; do not attempt role conversion against a live protected environment. The scripts deliberately do not modify any existing deployment automatically.

Do not run schema or role conversion against production or Surveillance databases without a separately reviewed and scheduled change. The fresh/upgrade acceptance used isolated PostgreSQL 16.13 and synthetic data; no existing Docker services, volumes or databases were altered. Before future usage counters/session tables or enforcement, use staged additive migrations, reconcile synthetic and real existing rows, verify indexes/query plans, and define forward repair/restore; never lower a tenant limit below current usage without explicit grandfathering or remediation policy.

### Phase 3.3C.1B acceptance result

**PASS — Phase 3.3C.1 foundation and PostgreSQL acceptance are closed.** The isolated `IdentityQuotaPersistenceAcceptanceTest` executed 7 test methods on PostgreSQL 16.13; all 7 passed, with 0 failures, errors or skips. It verified V1/V2/V3 migration history and catalog values/constraints, default assignment, legacy fallback, effective nullable overrides, rollback, invalid plan and default handling, active-membership access, authenticated/no-store HTTP behavior, OWNER invariants and V2 upgrade preservation.

Two issues found during acceptance were corrected before the final clean run:

- The effective-quota SQL had two bind placeholders but supplied one parameter. The repository now supplies the default plan and organization ID; the real PostgreSQL tests cover assigned and fallback resolution.
- A read-only quota request attempted to auto-discover an unknown OIDC subject, causing an INSERT in a read-only transaction. Quota reads now look up an existing Identity user only and return inaccessible-resource 404 when none exists; they do not mutate Identity data.

The first acceptance attempts were against separate scratch clusters and were not counted as passes. A final clean scratch cluster and fresh databases were used after fixes. No already-passing unit, controller, OIDC, HLS, Broadcast, Kafka/STOMP, browser or load tests were repeated.

## Open decisions before enforcement

- Whether count semantics include disabled/suspended Cameras (current definition: persisted, non-deleted Camera rows) and how camera tombstone retention affects restoration.
- Whether enabled-but-offline Broadcasts count (current definition: yes, `enabled=true`).
- How existing organizations are grandfathered if the default plan is below their current use.
- Who may assign plans/overrides, required audit evidence, expiry of temporary overrides, and approval controls.
- Viewer-session identity across browser reloads/devices, anonymous public anti-abuse limits, and whether a viewer may have multiple simultaneous sessions.
- Reconciliation intervals, stale reservation recovery, metrics/alerts and operator procedures.
- Cross-schema database grants and whether the shared-database transaction model remains supported for production.
- Stable API error envelope and UI warning/grace behavior.
- Whether a missing or disabled default plan should fail application startup rather than fail organization creation transactionally; current quota reads fail closed with 503 and the assignment FK prevents invalid plan assignment.

Billing, payment, invoices and public ingress are outside this phase.

### Phase 3.3C.2 focused verification (2026-11-25)

PostgreSQL 16.13 used a fresh temporary cluster under `/tmp` with loopback-only port 56439 and separate `quota_acceptance_phase33c2` and `quota_acceptance_migrations` databases. No Docker or existing deployment database/volume was used. The opted-in JUnit fixtures reject non-loopback URLs and database names outside `quota_acceptance_*`.

| Exact method | Result | Evidence |
|---|---|---|
| `com.coltrack.identity.quota.TenantQuotaGuardTest.rejectsAtLimitAfterAcquiringOrganizationLock` | PASS | JUnit XML: 1 test, 0 failures/errors/skips; verifies stable rejection after tenant lock. |
| `com.coltrack.identity.quota.TenantQuotaGuardTest.databaseLookupFailureFailsClosed` | PASS | JUnit XML: simulated PostgreSQL/Identity schema access failure maps to `QuotaAuthorityUnavailableException` before resource count. |
| `com.coltrack.cameraservice.service.CameraQuotaAdmissionTest.repeatedCreateWithSameKeyAndPayloadReturnsTheSameCameraWithoutSecondAdmission` | PASS | JUnit XML: 1 test; verifies idempotent result and only one quota admission/save. |
| `com.coltrack.cameraservice.service.CameraQuotaAdmissionTest.atLimitCameraCreationDoesNotPersist` | PASS | JUnit XML: 1 test; rejection occurs before Camera persistence. |
| `com.coltrack.streamservice.service.BroadcastQuotaLifecycleTest.activationReservesBeforePersistingEnabledState` | PASS | JUnit XML: 1 test; persisted transition follows capacity check. |
| `com.coltrack.streamservice.service.BroadcastQuotaLifecycleTest.quotaDenialLeavesBroadcastDisabled` | PASS | JUnit XML: 1 test; denial makes no enabled-state write. |
| `com.coltrack.cameraservice.controller.CameraControllerTenantAuthorizationTest.createAssignsTenantFromMembershipContext` | PASS | JUnit XML: 1 test; verified tenant and idempotency key passed to owner service. |
| `com.coltrack.cameraservice.controller.CameraControllerTenantAuthorizationTest.cameraUsageIsMembershipScopedAndNotCacheable` | PASS | Active Viewer receives tenant usage with `Cache-Control: no-store`. |
| `com.coltrack.cameraservice.controller.CameraControllerTenantAuthorizationTest.absentMembershipDeniesListAndCameraMutationsBeforeAnyLookup` | PASS | Directly updated method; usage read also returns 404 before service access. |
| `com.coltrack.streamservice.controller.BroadcastControllerTenantAuthorizationTest.broadcastUsageIsMembershipScopedAndNotCacheable` | PASS | Active Viewer receives tenant usage with `Cache-Control: no-store`. |
| `com.coltrack.streamservice.controller.BroadcastControllerTenantAuthorizationTest.missingMembershipDeniesBroadcastReadDeleteAndPlaybackInfoBeforeServiceCalls` | PASS | Directly updated method; usage read also returns 404 before service access. |
| `com.coltrack.cameraservice.service.TenantQuotaPostgresAcceptanceTest.concurrentCameraCreatesSerializeAtOneRemainingSlot` | PASS | PostgreSQL 16.13, two concurrent transactions, one starting slot; exactly one admission and final count 3. |
| `com.coltrack.cameraservice.service.TenantQuotaPostgresAcceptanceTest.concurrentBroadcastActivationsSerializeAtOneRemainingSlot` | PASS | PostgreSQL 16.13, two concurrent transactions, one slot; exactly one enabled row. |
| `com.coltrack.cameraservice.service.TenantQuotaPostgresAcceptanceTest.rollbackDoesNotConsumeCameraCapacityAndDeletionReleasesIt` | PASS | PostgreSQL 16.13; failed transaction left count unchanged, deletion released capacity. |
| `com.coltrack.cameraservice.service.TenantQuotaPostgresAcceptanceTest.loweringQuotaBelowUsagePreservesRowsAndRejectsOnlyFurtherCreation` | PASS | PostgreSQL 16.13; 2 existing Cameras remained at limit 1, remaining=0 and another admission rejected. |
| `com.coltrack.cameraservice.service.TenantQuotaPostgresAcceptanceTest.disablingBroadcastReleasesItsActiveSlot` | PASS | PostgreSQL 16.13; disabled enabled row then re-admitted under the same tenant lock. |
| `com.coltrack.cameraservice.service.TenantQuotaPostgresAcceptanceTest.concurrentCameraCreateAndDeleteNeverExceedLimit` | PASS | PostgreSQL 16.13, two transactions; either ordering ended at or below the one-Camera limit. |
| `com.coltrack.cameraservice.service.TenantQuotaPostgresAcceptanceTest.concurrentBroadcastEnableAndDisableNeverExceedLimit` | PASS | PostgreSQL 16.13, two transactions; enabling while another Broadcast disables never exceeded one active row. |
| `com.coltrack.cameraservice.service.CameraQuotaMigrationPostgresAcceptanceTest.cameraV1ThroughV4ApplyAdditivelyAndPreserveLegacyCameraRow` | PASS | Real Flyway ran V1–V4 plus baseline 0; synthetic unassigned Camera remained tenant-null and V4 table/column existed. |

The first PostgreSQL race attempt exposed a ResultSet callback positioning bug in the new lock lookup; it was fixed by using an explicit `ResultSetExtractor`, then the race passed after rerun. One initial migration assertion omitted Flyway baseline 0; the migration itself succeeded and the corrected assertion passed. The first lifecycle test attempt had a test verification mismatch, corrected and rerun; no application assertion remains failing. Identity-unavailable behavior was simulated at the JDBC boundary; no live service outage was induced. Cross-tenant resource-ID tests were not repeated because those authorization paths were unchanged; new usage routes were explicitly tested for active-member success, no-store and missing-membership denial.

These are focused guard/lifecycle/database tests, not a full live SaaS service deployment. PostgreSQL migrations and transaction locking are observed, while concurrent HTTP requests through multiple running Camera/Stream instances, actual Kafka delivery on rollback, deployed DB role grants, and recovery under process crash remain NOT RUN. No previous OIDC/HLS, Kafka/STOMP, browser or load tests were repeated. MAX_CONCURRENT_VIEWERS is NOT IMPLEMENTED.
