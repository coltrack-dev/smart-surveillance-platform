# Usage and quotas: Phase 3.3C.2 Camera and Broadcast enforcement

## Status

Implemented in Phase 3.3C.1/3.3C.2: an Identity-owned plan catalog, organization plan assignment and per-organization quota override schema; effective quota resolution; atomic Camera admission and active Broadcast transitions; authoritative tenant-scoped usage reads from the owning services; and Dashboard usage/limit indicators. Identity Flyway V1/V2/V3 passed in the earlier isolated PostgreSQL 16.13 acceptance. Camera Flyway V1–V4 and the new quota serialization scenarios passed against fresh isolated PostgreSQL 16.13 databases on 2026-11-25.

Not implemented: MAX_CONCURRENT_VIEWERS, plan/override administration, scheduled usage reconciliation, warnings, suspension, billing or payment processing. Camera and active Broadcast counts are queried from their authoritative tables and survive restarts without counters. Dashboard usage is informational; admission decisions remain server-side. Do not treat the illustrative plans as commercial commitments.

## Phase 3.3C.2 — Implemented behavior

Camera Service owns Camera persistence. Streaming creation requires an `Idempotency-Key`; the key is tenant-scoped, while a keyed HMAC of the canonical request is stored to distinguish a retry from key reuse with changed request data. The Camera row and idempotency record are committed in the same transaction. The HMAC uses the existing 32-byte Camera credential key with a domain-separated derivation; neither the plaintext RTSP password nor raw key is stored in the idempotency table. Same-key/same-payload retries return the existing Camera without consuming quota again. Same key with changed details, or a key whose Camera was deleted, returns 409.

Stream Service owns Broadcast persistence. Only a `false → true` transition consumes `MAX_ACTIVE_BROADCASTS`. An already-enabled retry is idempotent and does not reserve another slot. Creating a disabled Broadcast does not consume a slot. Disable and delete remain allowed when usage is at or above a lowered limit. Enabled/offline Broadcasts count because `enabled` is persisted desired state; StreamManager lease reconciliation restores media state separately.

Both resource services use one shared `TenantQuotaGuard` against the existing Streaming PostgreSQL database. In each admission/release transaction it obtains `pg_advisory_xact_lock(hashtextextended(tenant UUID, 0))`, verifies the tenant remains active, resolves the plan/overrides from the Identity schema, counts the resource in its owner table, and applies the write before commit. The advisory transaction lock serializes all Camera/Broadcast quota transitions for the same organization across service instances without requiring UPDATE privileges on `identity.tenants`. A hash collision only serializes unrelated tenants; it cannot admit extra usage. The Streaming Compose topology currently points Identity, Camera and Stream at the same PostgreSQL database and DB credential. A split-database deployment is unsupported by this strategy and fails closed when quota tables cannot be read.

No remote request is made while holding the quota lock. Camera registration Kafka publication happens after commit. Broadcast enabled state commits in a dedicated transactional lifecycle bean; StreamManager lease acquire/release follows after that bean returns, and the existing periodic desired-state reconciliation repairs media side effects after failures. The persisted count remains authoritative even if ingest is offline. Camera create rollback removes both row and idempotency record. Quota lowering does not mutate existing rows; only subsequent increases are refused.

Quota-exhaustion APIs return HTTP 409 with `CAMERA_QUOTA_EXCEEDED` or `ACTIVE_BROADCAST_QUOTA_EXCEEDED`, metric name, current usage and limit. Quota schema/database/configuration failures fail new admissions with HTTP 503 `QUOTA_AUTHORITY_UNAVAILABLE`; no fail-open fallback exists. Disable/delete are cleanup paths and do not need plan lookup, though they still require access to the owning resource database. Usage endpoints are `GET /api/v1/cameras/usage` and `GET /api/v1/broadcasts/usage` through Gateway, use the selected `X-Tenant-ID` only after membership authorization, and return `Cache-Control: no-store`. Each response includes `tenantId`, resource type, `used`, `limit`, and non-negative `remaining`. Camera usage counts existing Camera rows; Broadcast usage counts `enabled=TRUE` rows.

Camera Flyway V4 adds `camera_create_idempotency` without assigning tenant ownership or changing existing Camera rows. Stream needs no schema change for quota enforcement. Existing Surveillance requests with no tenant stay on the legacy path; the new migration is additive and the quota guard is only called for tenant-owned Streaming operations. Existing Surveillance data and volumes were not contacted during acceptance.

## Ownership and current sources of truth

| Resource / metric | Authoritative owner | Current persistence and semantics | Foundation status |
|---|---|---|---|
| Organization, membership, plan, quota override | Identity Service | `identity.tenants`, memberships and the new quota tables | Plan/config authority implemented |
| Cameras | Camera Service | `cameras.tenant_id`; a camera counts while its row exists. Delete is physical after writing the separate deletion tombstone. | No usage aggregation or enforcement |
| Active broadcasts | Stream Service | `broadcasts.tenant_id` and `broadcasts.enabled`; enabled is desired/authoritative active state, independent of source health. | No usage aggregation or enforcement |
| Concurrent viewers | Not currently persisted | Playback grants are stateless, short-lived signed tokens. The 32-slot semaphore limits simultaneous upstream HTTP fetches, not viewers. | No viewer usage or enforcement |

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

## Viewer sessions

Do not count HLS requests, segments, keys, TCP connections or the current 32 concurrent upstream fetch slots. A viewer is a logical playback session with a stable opaque UUID, tenant, broadcast, optional authenticated user, created/last-renewed/expiry timestamps and revoked/closed state. For PUBLIC playback, the user is anonymous; PRIVATE sessions bind to the validated user and tenant.

The player should create a random session ID once per player tab and reuse it across token renewals and ordinary reloads in the same tab (for example, `sessionStorage`, never as authorization). A grant binds the session ID and broadcast playback version. An atomic upsert/lease acquisition on renewal ensures retries do not double-count. Expiry reclaims abandoned sessions after browser crash; explicit close is best-effort only. Renewal extends the lease and must not restart the ingest pipeline. Membership is revalidated on renewal and by existing private-media authorization; revocation prevents renewal and media access according to current grant checks. Multi-instance deployments need shared PostgreSQL-backed session state, unique session identity, bounded expiry cleanup and idempotent renew/release operations. Public session creation also needs rate/abuse controls to prevent UUID churn.

Use a bounded cleanup job and indexes on `(tenant_id, expires_at)` and `(broadcast_id, expires_at)`. If session persistence is unavailable, refuse new viewer admissions rather than allowing unmetered sessions; existing grants should retain only their already-authorized short TTL. No session table is introduced in this foundation because the player protocol and grant format do not yet carry a session identity.

## Migration and rollout

Identity Flyway V3 is additive and PostgreSQL 16 compatible. On 2026-11-25 it was applied by Flyway in sequence after V1/V2 to a fresh isolated PostgreSQL 16.13 database; a separate isolated database was first migrated to V2, seeded with a synthetic tenant/user/OWNER membership, and upgraded to V3. Both histories recorded successful SQL migrations V1, V2 and V3. The upgrade preserved its tenant and OWNER membership and created no plan assignment/override for that legacy tenant. The fresh migration seeded the expected catalog and constraints. No production, Surveillance, preview, or Phase 3.3B.1 database was used.

V3 adds the plan catalog, explicit assignment and nullable per-metric override tables. It does not alter Camera, Broadcast, playback, membership or existing ownership rows, and does not automatically assign existing tenants; resolution falls back to the configured default plan. The new organization assignment is written transactionally with organization creation. Acceptance also verified that an OWNER insert failure rolls back the tenant and quota assignment together, and that existing deferred OWNER constraints remain installed.

Do not run this migration against production or Surveillance databases. The acceptance used two new temporary PostgreSQL 16.13 clusters bound to loopback; no existing Docker services, volumes or databases were accessed. Before release, retain normal Identity backup/restore and migration monitoring. Before future usage counters/session tables or enforcement, use staged additive migrations, reconcile synthetic and real existing rows, verify indexes/query plans, and define forward repair/restore; never lower a tenant limit below current usage without explicit grandfathering or remediation policy.

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
