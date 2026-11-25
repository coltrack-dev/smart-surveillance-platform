# Smart Surveillance Platform — реестр тестов

## Phase 3.2D multi-tenant acceptance results (2026-11-24)

**Phase 3.2D acceptance: PASS. Public ingress: NOT APPROVED.** Phase 3.2D.1 verified live encrypted playlist and AES-128 key retrieval through Gateway. Phase 3.2D.2 used a valid HMAC playback grant to verify absolute external-key URI rejection, redirect blocking, and encoded traversal rejection at the Stream HTTP boundary. No prior IDOR, Kafka/STOMP, Flyway, backup/restore, browser or load test was repeated.

### Phase 3.2D.1 — Encrypted HLS key HTTP evidence (2026-11-24)

Environment: Compose project `phase32d-accept-20261123`, isolated Streaming PostgreSQL and Keycloak, loopback Gateway `127.0.0.1:18082`, Stream service `127.0.0.1:18094`, synthetic HLS upstream `127.0.0.1:18088`. Source was an original short H.264 HLS output with `#EXT-X-KEY:METHOD=AES-128,URI="aes.key"` and a 16-byte key. No Docker image was built, no Surveillance resource was operated, and no Gradle task was run. Playback token TTL was 15 seconds for expiry checks. A browser was not used; segment decryption is **NOT TESTED**.

| HTTP scenario | Status / result | Evidence |
|---|---|---|
| Tenant A obtains PRIVATE playback grant (`GET /api/playback/{publicId}/token`, validated bearer and `X-Tenant-ID`) | 200 | Grant was accepted by the Gateway-routed media proxy. |
| Authorized encrypted playlist (`GET /broadcast-play/{publicId}/index.m3u8?token=…`) | 200 | `#EXT-X-KEY` key reference was rewritten to `/broadcast-play/{publicId}/aes.key?token=…`; raw `aes.key` URI and upstream host were absent from returned playlist. |
| Authorized key (`GET /broadcast-play/{publicId}/aes.key?token=…`) | 200 | Response was exactly 16 bytes and matched the generated key; SHA-256 `8df375a7b8d7d2dab7b92a5232d54e5aeb6ac2c5673428e98178d26cb116d8cb`. |
| Missing or empty playback grant | 401 / 401 | Both rejected by the HTTP media route. |
| Modified or forged grant | 401 / 401 | Key requests rejected. |
| Expired 15-second grant | 401 | Key request after 16 seconds. |
| Tenant B requests Tenant A PRIVATE playback grant | 401 | Cross-tenant grant denied. |
| Key after Tenant A membership revocation | 401 | Membership was restored immediately after this isolated check. |
| Key after Broadcast disable / delete | 401 / 404 | Disable management request returned 200; delete returned 204. |
| Malformed encoded key resource path | 400 | Rejected at HTTP boundary. |
| Authorized playlist with absolute external `EXT-X-KEY` URI | **PASS** | `BroadcastPlaybackHttpIsolationTest.validHmacGrantReachesValidatorAndRejectsAbsoluteEncryptionKeyUri`: real `PlaybackTokenService` issued an unexpired HMAC grant; real `BroadcastService.authorizePlayback` accepted it; MockMvc HTTP GET fetched only the expected synthetic source playlist and returned 502 from the URI validator for `https://outside.invalid/key`; `MockRestServiceServer.verify()` confirmed no request to that URI. |
| Captured application/proxy log scan | PASS (bounded) | No access/playback token, key bytes, key Base64 or key SHA-256 match in captured Stream/Gateway/upstream logs. No raw upstream URL appeared in playlist. |

**Decision:** the encrypted-key and hostile-URI Phase 3.2D acceptance items are **RESOLVED**. The Phase 3.2D security evidence matrix is **PASS**. Public ingress remains **NOT APPROVED** pending a separate route allowlist/security review and deployment controls. No browser AES decoding was tested. No previously passed IDOR, Kafka/STOMP, Flyway, backup/restore, browser or load tests were repeated.

An auxiliary re-enable request used while sequencing the synthetic disable/delete checks returned HTTP 500 because the minimal isolated stack did not include Camera service for stream reacquisition. The synthetic row was then removed; the two pre-existing synthetic Broadcast enabled flags and their two lease rows were restored from the saved pre-test snapshot. This is an environment limitation observed during fixture handling, not accepted as evidence that the normal full Streaming stack cannot re-enable a Broadcast.

### Phase 3.2D.2 — HLS URI SSRF boundary (2026-11-24)

The prior live request's 401 was produced before the source playlist fetch: `BroadcastPlaybackController.file` calls `BroadcastService.authorizePlayback` first, and only then fetches/re-writes the playlist. The retained live artifacts did not capture the grant expiry, persisted playback version, enabled state and membership snapshot at that instant. Thus the precise authorization subcondition is **UNKNOWN**; possible privacy-preserving 401 causes include invalid/expired signature, revoked membership, disabled Broadcast or stale playback version. This was a test-fixture observability gap, not evidence that URI validation returned 401.

Focused executions, each in a separate invocation with `--no-daemon --no-parallel --max-workers=1`, one `:services:stream-service:test` task and one exact `--tests` filter:

| Full test method | Result | Observed evidence |
|---|---|---|
| `com.coltrack.streamservice.controller.BroadcastPlaybackHttpIsolationTest.validHmacGrantReachesValidatorAndRejectsAbsoluteEncryptionKeyUri` | **PASSED** | Signed, unexpired HMAC grant verified by the real service; request reached the source playlist mock; response was 502 on external key URI; exactly the expected source request was verified and no external request was made. |
| `com.coltrack.streamservice.controller.BroadcastPlaybackHttpIsolationTest.encodedTraversalAndUnrecognizedUriAttributesFailClosed` | **PASSED** | Rejected percent/double-percent encoded traversal, encoded separators, query/fragment suffixes, case-variant absolute URI and unquoted URI attribute. |
| `com.coltrack.streamservice.controller.BroadcastPlaybackHttpIsolationTest.playbackProxyRejectsUpstreamRedirectWithoutContactingRedirectTarget` | **PASSED** | Local upstream 302 returned 502; local redirect target got zero requests and no Authorization header. |
| `com.coltrack.streamservice.controller.BroadcastPlaybackControllerTest.rewritesPlaylistSegmentsAndEncryptionKeyToProtectedUrls` | **PASSED** | Existing direct rewrite test rerun because the parser changed; current JUnit XML confirms one test, zero failures/errors/skips. |

Commands (run separately, one method per Gradle invocation):

```bash
./gradlew --no-daemon --no-parallel --max-workers=1 :services:stream-service:test --tests 'com.coltrack.streamservice.controller.BroadcastPlaybackHttpIsolationTest.validHmacGrantReachesValidatorAndRejectsAbsoluteEncryptionKeyUri'
./gradlew --no-daemon --no-parallel --max-workers=1 :services:stream-service:test --tests 'com.coltrack.streamservice.controller.BroadcastPlaybackHttpIsolationTest.encodedTraversalAndUnrecognizedUriAttributesFailClosed'
./gradlew --no-daemon --no-parallel --max-workers=1 :services:stream-service:test --tests 'com.coltrack.streamservice.controller.BroadcastPlaybackHttpIsolationTest.playbackProxyRejectsUpstreamRedirectWithoutContactingRedirectTarget'
./gradlew --no-daemon --no-parallel --max-workers=1 :services:stream-service:test --tests 'com.coltrack.streamservice.controller.BroadcastPlaybackControllerTest.rewritesPlaylistSegmentsAndEncryptionKeyToProtectedUrls'
```

The `BroadcastPlaybackHttpIsolationTest` XML was replaced by subsequent single-filter Gradle invocations; each run reported `BUILD SUCCESSFUL` and its exact one-method XML was inspected immediately after execution. The current persisted XML is for the final `BroadcastPlaybackControllerTest` invocation. Two initial invocations stopped at Java compilation (incorrect Spring request-factory import and a lambda parameter name collision); those compile errors were corrected before test execution, and no test assertion failed. No Docker, live Gateway, broad regression or load test was run in Phase 3.2D.2; the decisive URI test is a controller-level HTTP integration test with a valid signed grant and an upstream request mock.

### Source inventory at this snapshot

The current Java source scan finds **153 methods annotated `@Test`**. This is a source count, not the number of tests executed in Phase 3.2D; parameterized invocations and assertions are not expanded.

| Module | `@Test` methods in current sources |
|---|---:|
| camera-gateway | 6 |
| analytics-service | 24 |
| camera-service | 17 |
| identity-service | 5 |
| notification-service | 1 |
| recording-service | 24 |
| search-service | 1 |
| stream-service | 53 |
| websocket-service | 9 |
| common-security | 13 |
| **Total** | **153** |

The previous Phase 3.2C published snapshot listed 136 methods. The current scan is against the changed Phase 3.2D worktree; it must not be confused with the 15 unique focused methods executed below. The old snapshot was not a baseline for recomputing this changed tree. Earlier per-method historical statuses remain in the following inventory sections.

### Focused Java tests executed

| Full test method | Status | Evidence / note |
|---|---|---|
| `com.coltrack.cameraservice.controller.CameraControllerTenantAuthorizationTest.tenantCameraListUsesMembershipScopedRepositoryPath` | PASSED | Selected in the initial focused Phase 3.2D controller run; tenant-scoped service path only. |
| `com.coltrack.cameraservice.controller.CameraControllerTenantAuthorizationTest.absentMembershipDeniesListAndCameraMutationsBeforeAnyLookup` | PASSED | Selected in the initial focused Phase 3.2D run; no Camera/monitoring service access without membership. |
| `com.coltrack.cameraservice.controller.CameraControllerTenantAuthorizationTest.crossTenantCameraMutationsUseScopedLookupsAndReturnNotFound` | PASSED | Selected in the initial focused Phase 3.2D run; update/delete/heartbeat/check-stream use scoped operations and hide the row. |
| `com.coltrack.cameraservice.controller.CameraControllerTenantAuthorizationTest.crossTenantCameraGetIsHiddenWithoutUnscopedFallback` | PASSED | Exact filtered method in Phase 3.2D; cross-tenant GET returns 404 and does not call the unscoped lookup. |
| `com.coltrack.cameraservice.controller.CameraControllerTenantAuthorizationTest.unassignedLegacyCameraIsHiddenByTenantScopedReadAndMutationRoutes` | PASSED | Final exact rerun passed after fixing its missing mocked `findById(id, tenant)` result; the first attempt failed in the fixture with NPE, not in application code. |
| `com.coltrack.streamservice.controller.StreamTenantAuthorizationTest.missingMembershipDeniesEveryStreamRouteBeforeResourceLookup` | PASSED | Selected in the initial focused Phase 3.2D run; management/read/list/HLS routes are denied before service access. |
| `com.coltrack.streamservice.controller.StreamTenantAuthorizationTest.crossTenantStartReadUrlAndRemoveAreHiddenBeforeStreamManagerAccess` | PASSED | Selected in the initial focused Phase 3.2D run; cross-tenant camera resolution returns hidden 404 before manager calls. |
| `com.coltrack.streamservice.controller.StreamTenantAuthorizationTest.streamListsUseTenantScopedLeaseViews` | PASSED | Selected in the initial focused Phase 3.2D run; list and available-camera views use selected tenant. |
| `com.coltrack.streamservice.controller.StreamTenantAuthorizationTest.unassignedLegacyCameraCannotStartReadStatusOrReleaseAStreamLease` | PASSED | Exact filtered method in Phase 3.2D; unassigned record cannot start/read/status/stop/remove a stream. |
| `com.coltrack.streamservice.controller.StreamTenantAuthorizationTest.authorizedStartAndStopUseTenantSpecificConsumerLeaseOperations` | PASSED | Exact filtered method in Phase 3.2D; start/stop delegate only to tenant-bound lease operations. |
| `com.coltrack.streamservice.controller.BroadcastControllerTenantAuthorizationTest.missingMembershipDeniesBroadcastReadDeleteAndPlaybackInfoBeforeServiceCalls` | PASSED | Selected in the initial focused Phase 3.2D run; no service lookup occurs without membership. |
| `com.coltrack.streamservice.controller.BroadcastControllerTenantAuthorizationTest.crossTenantBroadcastReadsDeleteAndPlaybackInfoUseScopedServiceMethods` | PASSED | Selected in the initial focused Phase 3.2D run; get/list/delete/playback-info use tenant-scoped methods only. |
| `com.coltrack.streamservice.controller.BroadcastControllerTenantAuthorizationTest.unassignedLegacyBroadcastIsHiddenFromTenantListReadDeleteAndPlaybackInfo` | PASSED | Exact filtered method in Phase 3.2D; unassigned Broadcast is absent and mutations/details use scoped methods. |
| `com.coltrack.websocketservice.security.WebSocketHttpSecurityTest.oidcPermitsUpgradeButDeniesUnrelatedHttpRoutes` | PASSED | Exact filtered OIDC HTTP security method; WebSocket upgrade allowed for STOMP auth; unrelated HTTP route denied. |
| `com.coltrack.websocketservice.security.LegacyWebSocketHttpSecurityTest.legacyPermitsTrustedUpgradeButDeniesUnrelatedRoutes` | PASSED | Exact filtered legacy HTTP security method; trusted Surveillance upgrade remains available. |

Each Gradle `Test` task was invoked separately with `--no-daemon --no-parallel --max-workers=1` and a focused `--tests` filter; the follow-up tests added in this turn used exact method filters. No previously passed test was repeated solely for confirmation. The only method rerun in this phase was the new Camera method whose test fixture failed on its first attempt; the fixture was corrected and the same method then passed.

### Live isolated integration evidence

Environment: Compose project `phase32d-accept-20261123`; isolated PostgreSQL 17, Kafka, Keycloak, MediaMTX 1.13.1, Rust ingest-agent and service JARs. Host-facing test ports were loopback-only: Gateway 18082, WebSocket service 18096, PostgreSQL 25440, Keycloak 18180, Kafka 19092, MediaMTX RTSP 18554 and HLS 18888. Browser/test origin was `http://localhost:15173`. Two synthetic tenants and three Camera/Broadcast rows were used; the RTSP publisher generated 640×360 H.264 at 10 fps. No public ingress or load test was used.

| Scenario | Status | Exact result |
|---|---|---|
| Gateway STOMP CONNECT: anonymous, Tenant A, Tenant B | PASS | Anonymous socket closed without CONNECTED; A and B each received CONNECTED. |
| STOMP SUBSCRIBE destination isolation | PASS | A-to-B tenant topic and invalid `/topic/streams` both returned STOMP ERROR. |
| Kafka owner routing and forged claim | PASS | A Camera event carrying forged Tenant B reached only A through Gateway; internal HLS URL and diagnostic detail were redacted. |
| Membership revocation on active socket | PASS | After isolated membership status changed to REVOKED, a later Camera event was not delivered to A; membership was restored to ACTIVE. |
| PUBLIC/PRIVATE playback token policy | PASS | PUBLIC anonymous and A-authenticated grants returned 200; PRIVATE anonymous and Tenant B grant for A's Broadcast returned 401; A PRIVATE grant returned 200. |
| Protected MediaMTX playlists and segments | PASS | PUBLIC and PRIVATE playlists returned 200 `#EXTM3U`; referenced HLS segments returned 200 (about 1.9 KiB and 2.2 KiB). |
| Token expiry and modification | PASS | 15-second grant returned 401 at 17.0 seconds; modified grant returned 401 in the earlier isolated probe. |
| Membership revocation, disable/delete | PASS | Existing PRIVATE grant and renewal were denied on revocation; fresh grant was denied after disable; renewal returned 410; delete returned 204, playback-info 404 and previous grant 404. |
| Direct `/media-hls/**` bypass | PASS | Anonymous request 401; authenticated request 403. |
| Live encryption key fetch in original Phase 3.2D run | NOT RUN (historical snapshot) | That synthetic playlist had no `EXT-X-KEY`; superseded by the Phase 3.2D.1 live key retrieval evidence above and the Phase 3.2D.2 valid-grant URI validator test. |
| Surveillance WebSocket compatibility | PASS (historical unit only) | Reused `WebSocketEventTenantRoutingTest.legacySurveillanceKeepsExistingGlobalDestinations`; no Surveillance stack was started. |

### Flyway and data restore

| Scenario | Status | Exact evidence |
|---|---|---|
| Fresh database service migrations | PASS | Identity V1; Camera baseline 0 + V1–V3; Stream baseline 0 + V1–V3, all history rows `success=true`. |
| Populated pre-ownership schema backup/restore | PASS | Isolated `pg_dump -Fc`/`pg_restore` restored one Camera, one Broadcast and one lease (`1:1:1`); restored schema had no `tenant_id` columns before upgrade. |
| Service Flyway upgrade and no automatic ownership | PASS | Camera/Stream histories reached V3; legacy Camera/Broadcast `tenant_id` remained NULL and tenant-qualified queries returned `0:0`; five tenant indexes present. Stream startup later cleared the ephemeral `LEGACY` lease through existing orphan cleanup. |
| Post-ownership backup/restore | PASS | Synthetic Camera/Broadcast/lease IDs and owner UUID were identical after restore. |
| Destructive rollback after assignment | NOT RUN | Prohibited for assigned ownership data; no down migration. Backup/restore was tested instead. |

### Explicitly not run

- Playwright/Chromium page/iframe E2E: historical PASS from `docs/broadcast-integration-tests.md` reused; not repeated because player code was unchanged.
- Load/stress/soak or high-concurrency viewer test: NOT RUN by scope. The historical four-viewer and 32-slot tests are not new Phase 3.2D evidence.
- Existing Surveillance service/container/data operations: NOT RUN.
- Public ingress or production migration: NOT RUN.
- Live encrypted HLS key request: BLOCKED as noted above.

## Phase 3.2C cross-transport additions and execution record (2026-11-23)

The previous Phase 3.2B source snapshot was 118 Java test methods. A current source scan finds **136 methods** across the repository (`@Test` methods; parameterized invocations and assertions are not expanded). Current source counts: camera-gateway 6, analytics-service 24, camera-service 12, notification-service 1, recording-service 25, search-service 1, stream-service 42, websocket-service 7, identity-service 5, common-security 13. The full per-method table and historical statuses below remain the Phase 3.2B snapshot; do not interpret it as a current 136-method execution report. Exact results for this phase are listed separately.

### Exact Phase 3.2C test results

| Full test method | Result | Evidence |
|---|---|---|
| `com.coltrack.identity.security.TenantWorkloadJwtTest.decoderRejectsModifiedSignatureAndMissingTenant` | PASSED | JUnit XML, 2026-11-23 |
| `com.coltrack.identity.security.TenantWorkloadJwtTest.ownershipLookupTokenHasNarrowScopeAndNoTenantClaim` | PASSED | JUnit XML, 2026-11-23 |
| `com.coltrack.identity.security.TenantWorkloadJwtTest.signedWorkloadTokenCarriesValidatedTenantContext` | PASSED | JUnit XML, 2026-11-23 |
| `com.coltrack.cameraservice.security.CameraInternalWorkloadSecurityTest.internalConnectionRequiresSignedWorkloadAndUsesSignedTenantInsteadOfHeader` | PASSED | JUnit XML, 2026-11-23 |
| `com.coltrack.cameraservice.security.CameraInternalWorkloadSecurityTest.ownershipLookupRequiresDedicatedServiceScopeAndReturnsPersistedOwner` | PASSED | JUnit XML, 2026-11-23 |
| `com.coltrack.cameraservice.service.CameraStatusTenantEventTest.streamingDoesNotMutateOrPublishStatusForUnassignedCamera` | PASSED | JUnit XML, 2026-11-23 |
| `com.coltrack.cameraservice.service.CameraStatusTenantEventTest.streamingStatusMutationUsesPersistedCameraOwner` | PASSED | JUnit XML, 2026-11-23 |
| `com.coltrack.websocketservice.security.TenantStompSecurityInterceptorTest.activeSubscriptionStopsReceivingFramesAfterMembershipRevocation` | PASSED | Final JUnit XML, 2026-11-23 |
| `com.coltrack.websocketservice.security.TenantStompSecurityInterceptorTest.authenticatedMultiTenantUserMayConnectPerTenantButCannotSubscribeAcrossTenant` | PASSED | Final JUnit XML, 2026-11-23 |
| `com.coltrack.websocketservice.security.TenantStompSecurityInterceptorTest.connectRequiresJwtAndValidatedMembershipForSelectedTenant` | PASSED | Final JUnit XML, 2026-11-23 |
| `com.coltrack.websocketservice.consumer.WebSocketEventTenantRoutingTest.cameraStatusRoutesUsingPersistedOwnerAndRedactsReason` | PASSED | Final JUnit XML, 2026-11-23 |
| `com.coltrack.websocketservice.consumer.WebSocketEventTenantRoutingTest.legacySurveillanceKeepsExistingGlobalDestinations` | PASSED | Final JUnit XML, 2026-11-23 |
| `com.coltrack.websocketservice.consumer.WebSocketEventTenantRoutingTest.streamStatusRoutesFromPersistedOwnerAndDoesNotForwardInternalMediaDetails` | PASSED | Final JUnit XML, 2026-11-23 |
| `com.coltrack.websocketservice.consumer.WebSocketEventTenantRoutingTest.unassignedAndUnknownKafkaResourcesAreNotBroadcast` | PASSED | Final JUnit XML, 2026-11-23 |
| `com.coltrack.streamservice.controller.BroadcastPlaybackHttpIsolationTest.privateGrantRevokedBeforeHttpProxyDoesNotReachMediaMtx` | PASSED | Filtered stream-service run; upstream mocked |
| `com.coltrack.streamservice.controller.BroadcastPlaybackHttpIsolationTest.authorizedHttpPlaylistProxyRewritesEveryMediaReference` | PASSED | Filtered stream-service run; upstream mocked |
| `com.coltrack.streamservice.controller.BroadcastPlaybackHttpIsolationTest.privateTokenHttpEndpointDoesNotIssueAnonymousGrant` | PASSED | Filtered stream-service run |
| `com.coltrack.streamservice.controller.BroadcastControllerTenantAuthorizationTest.viewerCannotEnableOrCreateBroadcastAndCannotAcquireBroadcastLease` | PASSED | Filtered stream-service run |
| `com.coltrack.streamservice.controller.BroadcastControllerTenantAuthorizationTest.managementMutationUsesSelectedValidatedTenantForObjectLookup` | PASSED | Filtered stream-service run |
| `com.coltrack.streamservice.service.BroadcastTenantOwnershipTest.creatingBroadcastChecksCameraThroughTenantDelegationAndPersistsOwner` | PASSED | Filtered stream-service run |
| `com.coltrack.streamservice.service.BroadcastTenantOwnershipTest.privatePlaybackIsRevokedWhenMembershipIsRevoked` | PASSED | Filtered stream-service run |
| `com.coltrack.streamservice.service.BroadcastTenantOwnershipTest.crossTenantBroadcastLookupUsesTenantQualifiedQueryAndReturnsNotFound` | PASSED | Filtered stream-service run |
| `com.coltrack.streamservice.service.BroadcastTenantOwnershipTest.creationDoesNotClaimUnassignedLegacyBroadcast` | PASSED | Filtered stream-service run |
| `com.coltrack.streamservice.service.BroadcastTenantOwnershipTest.unassignedPublicBroadcastIsNotPlayableInStreamingMode` | PASSED | Filtered stream-service run |
| `com.coltrack.streamservice.service.BroadcastTenantOwnershipTest.privatePlaybackGrantFromAnotherTenantIsRejected` | PASSED | Filtered stream-service run |
| `com.coltrack.streamservice.consumer.CameraEventsConsumerTenantTest.streamingIgnoresUnscopedCameraDeletionEvents` | PASSED | Filtered stream-service run |
| `com.coltrack.streamservice.consumer.CameraEventsConsumerTenantTest.streamingScopesCameraDeletionCleanupByTenant` | PASSED | Initial run exposed only a Mockito verification assertion; the corrected test method was run alone and passed |
| `com.coltrack.gateway.security.StreamingSecurityConfigurationTest.deniesLegacyMediaAndAllowsWebSocketHandshakeForStompLayerAuthorization` | PASSED | Filtered Gateway XML |

Run scope: common-security 3 methods; camera-service 4 methods; websocket-service 7 methods; stream-service 13 methods across four selected classes (12 passed initially; the one corrected assertion method then passed on its exact rerun); Gateway 1 method. Compile-only checks passed for websocket-service, camera-service, stream-service, camera-gateway and recording-service. Each Gradle invocation contained one module `Test` task and used `--no-daemon --no-parallel --max-workers=1` with `--tests`. No full regression, live broker, browser, HLS pipeline, load test, or existing deployment was run.

Static validation: both `docker compose ... config -q` commands for demo and streaming passed, as did `git diff --check`. Migration SQL was applied via `psql` to an isolated PostgreSQL 17 tmpfs container, not through service Flyway startup. Synthetic legacy Camera/Broadcast/lease rows remained unassigned and tenant-qualified lookup returned zero. Flyway history/startup and rollback remain BLOCKED. See `docs/saas/SECURITY_BOUNDARIES.md` for the security evidence matrix.

## Phase 3.2B additions and execution record (2026-11-23)

На последнем снимке перед Phase 3.2B реестр содержал 98 Java test methods. Добавлено 20 методов; текущее число исходных Java методов — **118**. Считаются методы, а не assertions или parameterized invocations.

| Module | Полное имя метода | Результат Phase 3.2B |
|---|---|---|
| common-security | `com.coltrack.identity.security.TenantPermissionsTest.ownerAndAdminCanManageResources` | **PASSED** |
| common-security | `com.coltrack.identity.security.TenantPermissionsTest.operatorCanOperateButViewerCannotModifyResources` | **PASSED** |
| common-security | `com.coltrack.identity.security.TenantWorkloadJwtTest.signedWorkloadTokenCarriesValidatedTenantContext` | **PASSED после исправления**; первая попытка падала из-за невалидного issuer формата URI. |
| common-security | `com.coltrack.identity.security.TenantWorkloadJwtTest.decoderRejectsModifiedSignatureAndMissingTenant` | **PASSED** в том же запуске, где первый метод упал. |
| camera-service | `com.coltrack.cameraservice.service.CameraTenantOwnershipTest.tenantLookupHidesUnknownAndOtherTenantCameras` | **PASSED** в фильтрованном запуске; XML был заменён последующим фильтрованным запуском. |
| camera-service | `com.coltrack.cameraservice.service.CameraTenantOwnershipTest.legacyLookupRemainsUnscopedForSurveillance` | **PASSED** в фильтрованном запуске; XML был заменён последующим фильтрованным запуском. |
| camera-service | `com.coltrack.cameraservice.service.CameraTenantOwnershipTest.streamingCreateTakesOwnerOnlyFromVerifiedServiceArgument` | **PASSED** в фильтрованном запуске; XML был заменён последующим фильтрованным запуском. |
| camera-service | `com.coltrack.cameraservice.controller.CameraControllerTenantAuthorizationTest.createAssignsTenantFromMembershipContext` | **PASSED** |
| camera-service | `com.coltrack.cameraservice.controller.CameraControllerTenantAuthorizationTest.missingMembershipDoesNotFallBackToUnscopedCameraLookup` | **PASSED** |
| camera-service | `com.coltrack.cameraservice.security.CameraInternalWorkloadSecurityTest.internalConnectionRequiresSignedWorkloadAndUsesSignedTenantInsteadOfHeader` | **PASSED после исправления fixture**; первый WebMvc slice не имел KafkaProperties bean. |
| stream-service | `com.coltrack.streamservice.controller.StreamTenantAuthorizationTest.viewerCannotStopCameraStream` | **PASSED** |
| stream-service | `com.coltrack.streamservice.controller.StreamTenantAuthorizationTest.tenantMemberCannotStopAnotherTenantsCamera` | **PASSED** |
| stream-service | `com.coltrack.streamservice.service.BroadcastTenantOwnershipTest.creatingBroadcastChecksCameraThroughTenantDelegationAndPersistsOwner` | **PASSED** |
| stream-service | `com.coltrack.streamservice.service.BroadcastTenantOwnershipTest.creationDoesNotClaimUnassignedLegacyBroadcast` | **PASSED**; exact-method run and directly related owner-create test both passed. |
| stream-service | `com.coltrack.streamservice.service.BroadcastTenantOwnershipTest.unassignedPublicBroadcastIsNotPlayableInStreamingMode` | **PASSED**; unassigned PUBLIC rows have no token path in OIDC mode. |
| stream-service | `com.coltrack.streamservice.service.BroadcastTenantOwnershipTest.privatePlaybackIsRevokedWhenMembershipIsRevoked` | **PASSED** |
| stream-service | `com.coltrack.streamservice.service.BroadcastTenantOwnershipTest.crossTenantBroadcastLookupUsesTenantQualifiedQueryAndReturnsNotFound` | **PASSED** |
| stream-service | `com.coltrack.streamservice.consumer.CameraEventsConsumerTenantTest.streamingIgnoresUnscopedCameraDeletionEvents` | **PASSED** |
| stream-service | `com.coltrack.streamservice.consumer.CameraEventsConsumerTenantTest.streamingScopesCameraDeletionCleanupByTenant` | **PASSED** |
| stream-service | `com.coltrack.streamservice.service.BroadcastServiceTest.legacySurveillanceStillAllowsEnabledPublicBroadcastPlayback` | **PASSED**; legacy constructor still supports enabled PUBLIC playback. |

### Повторно выполненные существующие методы

| Полное имя метода | Результат и причина |
|---|---|
| `com.coltrack.streamservice.controller.BroadcastPlaybackControllerTest.rewritesPlaylistSegmentsAndEncryptionKeyToProtectedUrls` | **PASSED**; изменился constructor fixture контроллера из-за TenantAuthorization. |
| `com.coltrack.streamservice.controller.BroadcastPlaybackControllerTest.rejectsExternalPlaylistReferences` | **PASSED**; Gradle запускал выбранный класс целиком. |
| `com.coltrack.streamservice.controller.BroadcastPlaybackControllerTest.rejectsUnrecognizedKeyUriSyntaxInsteadOfLeavingItUnprotected` | **PASSED**; Gradle запускал выбранный класс целиком. |
| `com.coltrack.streamservice.controller.BroadcastPlaybackControllerTest.capsConcurrentUpstreamRequestsAt32AndReleasesSlotsAfterCompletion` | **PASSED**; уже имел исторический PASS, но попал под class filter вместе с затронутым playback controller. Этот повтор был лишним и зафиксирован прозрачно. |
| `com.coltrack.identityservice.service.RolePermissionEvaluatorTest.deniesPermissionWhenTenantMembershipDoesNotExist` | **PASSED**; evaluator теперь использует общую permission matrix. |
| `com.coltrack.identityservice.service.RolePermissionEvaluatorTest.viewerCanViewPlaybackButCannotManageBroadcasts` | **PASSED**; evaluator теперь использует общую permission matrix. |
| `com.coltrack.streamservice.service.BroadcastServiceTest.privateBroadcastDoesNotReceiveAnonymousPlaybackToken` | **PASSED**; rerun by exact method because private playback authorization changed. |
| `com.coltrack.identity.security.TenantWorkloadJwtTest.decoderRejectsModifiedSignatureAndMissingTenant` | **PASSED**; exact-method rerun after correcting issuer format confirmed tampering is rejected. |
| `com.coltrack.streamservice.service.BroadcastTenantOwnershipTest.creatingBroadcastChecksCameraThroughTenantDelegationAndPersistsOwner` | **PASSED**; rerun alongside the new unassigned-row test after create logic was hardened. |

Каждый Gradle запуск содержал одну `Test` задачу одного модуля, с `--no-parallel --max-workers=1`; полный regression, Docker, браузерные, нагрузочные тесты и миграции не запускались. Фильтрованные Gradle запуски очищали XML предыдущего invocation этого модуля, поэтому часть ранее полученных поимённых XML больше не лежит на диске; таблица сохраняет зафиксированный вывод запуска и явно отмечает этот факт. Compile-only проверки `common-security`, `camera-service`, `stream-service`, `identity-service` прошли. Статическая проверка `docker compose ... config -q` для demo и streaming прошла; контейнеры не запускались.

### NOT RUN в Phase 3.2B

Camera list/update/delete/check-stream controller permutations; Broadcast management endpoint role/IDOR matrix; HTTP-level public/private token, expiry/tamper/revocation; PUBLIC/PRIVATE browser playback; миграции даже на изолированной БД; recovery с настоящими PostgreSQL/Kafka; WebSocket tenant authorization/fanout; Recording/Analytics isolation; широкие regression/integration suites. Исторические PASS не заменяют эти проверки.

Актуальность реестра: исходники и XML на 2026-11-23, а также исторические результаты в `docs/broadcast-test-progress.md` / `docs/broadcast-integration-tests.md`. Текущие Phase 3.2B дополнительные методы и запуски перечислены выше; предыдущие статусы остальных методов сохранены как исторические. Docker-сервисы не запускались.

## Метод подсчёта

- Java-реестр содержит один ряд на метод с `@Test`, с полным именем класса и метода. Assertions внутри метода отдельными тестами не считаются. Параметризованные случаи также не разворачиваются в отдельные методы. В найденных Java test sources аннотаций `@ParameterizedTest` нет.
- Статус `PASSED`, `FAILED` или `SKIPPED` берётся из JUnit XML либо сохранённых исторических отчётов. 24 исходных stream-service метода получили PASS в ограниченном повторном запуске Phase 3.2A; при последующем узком запуске XML был заменён отчётом только по новому security test class. 22 ранее NOT RUN метода других модулей повторно не запускались.
- `NOT RUN` означает, что среди доступных отчётов и журналов не найдено надёжного результата для метода. Это не утверждение, что метод никогда не запускался.

## Сводка Java/JUnit по модулю

| Модуль | Методов в исходниках | PASSED | FAILED | SKIPPED | NOT RUN |
|---|---:|---:|---:|---:|---:|
| camera-gateway | 6 | 6 | 0 | 0 | 0 |
| analytics-service | 24 | 22 | 0 | 0 | 2 |
| camera-service | 9 | 7 | 0 | 0 | 2 |
| notification-service | 1 | 0 | 0 | 0 | 1 |
| recording-service | 24 | 8 | 0 | 0 | 16 |
| search-service | 1 | 0 | 0 | 0 | 1 |
| stream-service | 36 | 36 | 0 | 0 | 0 |
| common-security | 12 | 12 | 0 | 0 | 0 |
| identity-service | 5 | 5 | 0 | 0 | 0 |
| **Всего** | **118** | **96** | **0** | **0** | **22** |

На диске JUnit XML для нескольких затронутых модулей заменены последовательными фильтрованными запусками; точный текущий охват и утраченные отчёты описаны в Phase 3.2B execution record выше. Сводная таблица объединяет сохранённые исторические PASS, новые подтверждённые PASS и 22 NOT RUN; это не означает, что все 93 PASS были выполнены в текущем запуске.

## Полный список Java/JUnit методов

### camera-gateway

| Полное имя теста | Статус |
|---|---|
| `com.coltrack.gateway.GatewayApplicationTests.contextLoads` | **PASSED** |
| `com.coltrack.gateway.security.LegacySecurityConfigurationTest.preservesUnauthenticatedTrustedSurveillanceGatewayAccess` | **PASSED** |
| `com.coltrack.gateway.security.StreamingSecurityConfigurationTest.requiresAuthenticationForManagementRoutes` | **PASSED** |
| `com.coltrack.gateway.security.StreamingSecurityConfigurationTest.authenticatedJwtCanReachManagementRoutes` | **PASSED** |
| `com.coltrack.gateway.security.StreamingSecurityConfigurationTest.deniesLegacyMediaAndWebSocketRoutes` | **PASSED** |
| `com.coltrack.gateway.security.StreamingSecurityConfigurationTest.permitsPublicPlaybackRoutesToReachBroadcastPolicy` | **PASSED** |

### analytics-service

| Полное имя теста | Статус |
|---|---|
| `com.coltrack.analyticsservice.AnalyticsServiceApplicationTests.contextLoads` | **PASSED** |
| `com.coltrack.analyticsservice.dto.AnalyticsJobResponseTest.hidesStandardRtspPassword` | **PASSED** |
| `com.coltrack.analyticsservice.dto.AnalyticsJobResponseTest.hidesXmRtspPassword` | **PASSED** |
| `com.coltrack.analyticsservice.handler.AnalyticsEventHandlerRegistryTest.shouldDispatchEventToMatchingHandler` | **PASSED** |
| `com.coltrack.analyticsservice.handler.AnalyticsEventHandlerRegistryTest.shouldRejectDuplicateHandlerRegistration` | **PASSED** |
| `com.coltrack.analyticsservice.handler.AnalyticsEventHandlerRegistryTest.shouldRejectUnsupportedEventType` | **PASSED** |
| `com.coltrack.analyticsservice.handler.ObjectDetectedEventHandlerTest.shouldHandleObjectDetectedEvent` | **PASSED** |
| `com.coltrack.analyticsservice.handler.ObjectDetectedEventHandlerTest.shouldSupportObjectDetectedEvents` | **PASSED** |
| `com.coltrack.analyticsservice.service.AnalyticsControlServiceTest.realtimeStopMustTargetTheRunningJobId` | **PASSED** |
| `com.coltrack.analyticsservice.service.AnalyticsControlServiceTest.shouldAllowProgressUpdatesWhileRunning` | **PASSED** |
| `com.coltrack.analyticsservice.service.AnalyticsControlServiceTest.shouldCompleteRequestedStopWithStoppedStatus` | **PASSED** |
| `com.coltrack.analyticsservice.service.AnalyticsControlServiceTest.shouldCreateRecordingJobBeforePublishingIt` | **PASSED** |
| `com.coltrack.analyticsservice.service.AnalyticsControlServiceTest.shouldIgnoreCompletedWhileStopIsRequested` | **PASSED** |
| `com.coltrack.analyticsservice.service.AnalyticsControlServiceTest.shouldIgnoreRunningStatusAfterCompletion` | **PASSED** |
| `com.coltrack.analyticsservice.service.AnalyticsControlServiceTest.shouldResolveRealtimeSourceFromCameraServiceWhenOverrideIsEmpty` | **PASSED** |
| `com.coltrack.analyticsservice.service.AnalyticsControlServiceTest.shouldReturnConflictWhenDatabaseRejectsSecondActiveJob` | **PASSED** |
| `com.coltrack.analyticsservice.service.AnalyticsControlServiceTest.shouldReturnEmptyOptionalWhenRealtimeJobDoesNotExist` | **NOT RUN** |
| `com.coltrack.analyticsservice.service.AnalyticsControlServiceTest.shouldReturnEmptyOptionalWhenRecordingJobDoesNotExist` | **NOT RUN** |
| `com.coltrack.analyticsservice.service.AnalyticsEventServiceTest.shouldFindEventsForRecordingJob` | **PASSED** |
| `com.coltrack.analyticsservice.service.AnalyticsEventServiceTest.shouldFindPageContainingFirstEventAtVideoTime` | **PASSED** |
| `com.coltrack.analyticsservice.service.AnalyticsEventServiceTest.shouldReturnRecordingEventsForTimeline` | **PASSED** |
| `com.coltrack.analyticsservice.service.AnalyticsEventServiceTest.shouldSaveNewEvent` | **PASSED** |
| `com.coltrack.analyticsservice.service.AnalyticsEventServiceTest.shouldSkipEventInsertedConcurrently` | **PASSED** |
| `com.coltrack.analyticsservice.service.AnalyticsEventServiceTest.shouldSkipExistingEvent` | **PASSED** |

### camera-service

| Полное имя теста | Статус |
|---|---|
| `com.coltrack.cameraservice.CameraServiceApplicationTests.contextLoads` | **PASSED** |
| `com.coltrack.cameraservice.service.CameraCredentialsTest.resolvesStandardUrlWhenOptionalUsernameIsMissing` | **PASSED** |
| `com.coltrack.cameraservice.service.CameraCredentialsTest.encryptsAndDecryptsPassword` | **NOT RUN** |
| `com.coltrack.cameraservice.service.CameraCredentialsTest.resolvesXmTemplateWithoutPersistingPlaintextPassword` | **NOT RUN** |

### notification-service

| Полное имя теста | Статус |
|---|---|
| `com.coltrack.notificationservice.NotificationServiceApplicationTests.contextLoads` | **NOT RUN** |

### recording-service

| Полное имя теста | Статус |
|---|---|
| `com.coltrack.recordingservice.RecordingServiceApplicationTests.contextLoads` | **NOT RUN** |
| `com.coltrack.recordingservice.service.RecordingPlaybackServiceTest.refusesPlaybackWhenOnlyMissingS3CatalogRowsRemain` | **PASSED** |
| `com.coltrack.recordingservice.service.RecordingQueryServiceTest.protectsRecordingAndReportsHybridStorage` | **NOT RUN** |
| `com.coltrack.recordingservice.service.RecordingQueryServiceTest.rejectsInvalidTimeRange` | **NOT RUN** |
| `com.coltrack.recordingservice.service.RecordingQueryServiceTest.reportsMissingWhenOnlyUnverifiedCatalogRowsRemain` | **NOT RUN** |
| `com.coltrack.recordingservice.service.RecordingQueryServiceTest.reportsStorageCapacityAndCatalogedSize` | **NOT RUN** |
| `com.coltrack.recordingservice.service.RecordingStorageCleanupServiceTest.excludesLegacySharedDirectory` | **PASSED** |
| `com.coltrack.recordingservice.service.RecordingStorageCleanupServiceTest.excludesLocalOnlyRecordingByDefault` | **PASSED** |
| `com.coltrack.recordingservice.service.RecordingStorageCleanupServiceTest.previewsExpiredHybridRecordingWithoutDeletingIt` | **PASSED** |
| `com.coltrack.recordingservice.service.RecordingStorageCleanupServiceTest.reportsCleanupRequiredWhenDiskThresholdIsBreachedWithoutCandidates` | **PASSED** |
| `com.coltrack.recordingservice.service.RecordingStorageGuardTest.permitsRecordingBelowLimits` | **NOT RUN** |
| `com.coltrack.recordingservice.service.RecordingStorageGuardTest.rejectsNewRecordingAtLocalLimit` | **NOT RUN** |
| `com.coltrack.recordingservice.service.RecordingStoragePostgresIntegrationTest.advisoryLockRejectsConcurrentCleanupAcrossConnections` | **NOT RUN** |
| `com.coltrack.recordingservice.service.RecordingStoragePostgresIntegrationTest.migrationCreatesStorageSchemaAndRejectsDuplicateActiveS3Keys` | **NOT RUN** |
| `com.coltrack.recordingservice.service.RecordingStorageServiceTest.deletesOnlyDirectoryInsideConfiguredStorageRoot` | **NOT RUN** |
| `com.coltrack.recordingservice.service.RecordingStorageServiceTest.rejectsOverlappingThresholds` | **NOT RUN** |
| `com.coltrack.recordingservice.service.RecordingStorageServiceTest.resolvesHealthFromConfiguredThresholds` | **NOT RUN** |
| `com.coltrack.recordingservice.service.RecordingStorageServiceTest.snapshotMeasuresFilesRecursively` | **NOT RUN** |
| `com.coltrack.recordingservice.service.S3ReconciliationServiceTest.verifiesCatalogObjectsAndDetectsMissingObject` | **PASSED** |
| `com.coltrack.recordingservice.service.S3StorageManagementServiceTest.excludesHybridRecordingByDefault` | **NOT RUN** |
| `com.coltrack.recordingservice.service.S3StorageManagementServiceTest.previewsExpiredS3OnlyRecording` | **NOT RUN** |
| `com.coltrack.recordingservice.service.S3StorageManagementServiceTest.refusesDeletionUntilExplicitlyEnabled` | **NOT RUN** |
| `com.coltrack.recordingservice.service.S3StorageServiceTest.deletesEveryVersionAndDeleteMarker` | **PASSED** |
| `com.coltrack.recordingservice.service.S3StorageServiceTest.skipsPutWhenCatalogAndRemoteObjectAlreadyMatch` | **PASSED** |

### search-service

| Полное имя теста | Статус |
|---|---|
| `com.coltrack.searchservice.SearchServiceApplicationTests.contextLoads` | **NOT RUN** |

### stream-service

| Полное имя теста | Статус |
|---|---|
| `com.coltrack.streamservice.client.MediaHlsClientTest.rejectsExternalAndParentReferences` | **PASSED** |
| `com.coltrack.streamservice.client.MediaHlsClientTest.selectsMediaPlaylistAndLatestSegment` | **PASSED** |
| `com.coltrack.streamservice.config.IngestAgentPropertiesTest.buildsMediaMtxPublishAndPublicHlsUrls` | **PASSED** |
| `com.coltrack.streamservice.config.RtspStreamPropertiesTest.hasSafeNvrDefaults` | **PASSED** |
| `com.coltrack.streamservice.controller.BroadcastPlaybackControllerTest.capsConcurrentUpstreamRequestsAt32AndReleasesSlotsAfterCompletion` | **PASSED** |
| `com.coltrack.streamservice.controller.BroadcastPlaybackControllerTest.rejectsExternalPlaylistReferences` | **PASSED** |
| `com.coltrack.streamservice.controller.BroadcastPlaybackControllerTest.rejectsUnrecognizedKeyUriSyntaxInsteadOfLeavingItUnprotected` | **PASSED** |
| `com.coltrack.streamservice.controller.BroadcastPlaybackControllerTest.rewritesPlaylistSegmentsAndEncryptionKeyToProtectedUrls` | **PASSED** |
| `com.coltrack.streamservice.service.BroadcastServiceTest.enablingBroadcastAcquiresIndependentLeaseForExistingStream` | **PASSED** |
| `com.coltrack.streamservice.service.BroadcastServiceTest.privateBroadcastDoesNotReceiveAnonymousPlaybackToken` | **PASSED** |
| `com.coltrack.streamservice.service.BroadcastServiceTest.streamSessionJsonDoesNotExposeSourceRtspCredentials` | **PASSED** |
| `com.coltrack.streamservice.service.PlaybackTokenServiceTest.acceptsOnlyMatchingUnexpiredBroadcastToken` | **PASSED** |
| `com.coltrack.streamservice.service.StreamManagerLeaseTest.concurrentAcquireCallsReuseTheSameActiveSession` | **PASSED** |
| `com.coltrack.streamservice.service.StreamManagerLeaseTest.lastConsumerReleaseRequestsExactlyOneStop` | **PASSED** |
| `com.coltrack.streamservice.service.StreamManagerLeaseTest.legacyStopReleasesOnlyLegacyLease` | **PASSED** |
| `com.coltrack.streamservice.service.StreamManagerLeaseTest.releasingBroadcastLeaseDoesNotStopStreamWhileLegacyConsumerRemains` | **PASSED** |
| `com.coltrack.streamservice.worker.CameraStreamWorkerTest.emptyOrHeaderOnlyPlaylistIsNotReady` | **PASSED** |
| `com.coltrack.streamservice.worker.CameraStreamWorkerTest.playlistCannotReferenceFileOutsideStreamDirectory` | **PASSED** |
| `com.coltrack.streamservice.worker.CameraStreamWorkerTest.playlistIsNotReadyUntilReferencedSegmentExists` | **PASSED** |
| `com.coltrack.streamservice.worker.CameraStreamWorkerTest.usesWallclockTimestampsForRtspInput` | **PASSED** |
| `com.coltrack.streamservice.worker.IngestAgentStreamWorkerTest.doesNotPublishRunningBeforeHlsIsReady` | **PASSED** |
| `com.coltrack.streamservice.worker.IngestAgentStreamWorkerTest.mapsExistingVideoModesToAgentContract` | **PASSED** |
| `com.coltrack.streamservice.worker.IngestAgentStreamWorkerTest.recreatesMissingPipelineWithTheSameCommandId` | **PASSED** |
| `com.coltrack.streamservice.worker.IngestAgentStreamWorkerTest.retriesAfterTemporaryAgentCommunicationFailure` | **PASSED** |
| `com.coltrack.streamservice.security.StreamingResourceSecurityConfigurationTest.requiresAuthenticationForManagementRoutes` | **PASSED** |
| `com.coltrack.streamservice.security.StreamingResourceSecurityConfigurationTest.permitsPlaybackPolicyRoutesAndRequiresTheirOwnGrant` | **PASSED** |

### common-security

| Полное имя теста | Статус |
|---|---|
| `com.coltrack.security.OidcJwtValidatorsTest.acceptsTokenWithExpectedIssuerAudienceAndLifetime` | **PASSED** |
| `com.coltrack.security.OidcJwtValidatorsTest.rejectsWrongIssuer` | **PASSED** |
| `com.coltrack.security.OidcJwtValidatorsTest.rejectsMissingAudience` | **PASSED** |
| `com.coltrack.security.OidcJwtValidatorsTest.rejectsExpiredToken` | **PASSED** |
| `com.coltrack.security.OidcJwtValidatorsTest.rejectsTokenBeforeNotBeforeTime` | **PASSED** |
| `com.coltrack.security.OidcJwtSignatureTest.validatesRsaSignatureAndRejectsModifiedSignature` | **PASSED** |
| `com.coltrack.identity.security.MembershipResolverTest.tenantSelectorDoesNotCreateMembership` | **PASSED** |
| `com.coltrack.identity.security.MembershipResolverTest.resolvedTenantContextComesFromMembership` | **PASSED** |

### identity-service

| Полное имя теста | Статус |
|---|---|
| `com.coltrack.identityservice.controller.IdentityControllerSecurityTest.rejectsAnonymousIdentityDiscovery` | **PASSED** |
| `com.coltrack.identityservice.controller.IdentityControllerSecurityTest.returnsMembershipsForValidatedJwtPrincipal` | **PASSED** |
| `com.coltrack.identityservice.controller.IdentityControllerSecurityTest.returnsDiscoveredUserFromAuthenticatedIdentity` | **PASSED** |
| `com.coltrack.identityservice.service.RolePermissionEvaluatorTest.deniesPermissionWhenTenantMembershipDoesNotExist` | **PASSED** |
| `com.coltrack.identityservice.service.RolePermissionEvaluatorTest.viewerCanViewPlaybackButCannotManageBroadcasts` | **PASSED** |

## Python integration tests

Это standalone сценарии/скрипты, а не JUnit методы; проверки внутри скрипта не суммируются как отдельные методы. По существующим журналам и документации:

| Сценарий | Статус | Зафиксированный результат |
|---|---|---|
| `scripts/broadcast-integration-test.py` | **PASSED** | Регистрация камеры, отказ неавторизованного management доступа, идемпотентное создание, private-by-default, постоянные watch/embed URLs, tamper/expiry/revocation токена, безопасные пути, защищённые playlist/segments, совместное использование Broadcast и legacy Stream lease, disable/enable/delete. |
| `scripts/broadcast-mediamtx-auth-check.py` | **PASSED** | HTTP HLS/RTSP allow/deny; отсутствующие/неверные credentials отклонены; publisher может publish, reader не может; секреты не обнаружены в просмотренных логах. |
| `scripts/broadcast-recovery-test.py` | **PASSED (до перезагрузки хоста)** | Поочерёдный recovery после рестартов stream-service, ingest-agent, MediaMTX и PostgreSQL; защищённый HLS восстановился, по одному FFmpeg на source. |
| Отдельный camera network disconnect/reconnect | **NOT RUN** | Документация отмечает, что это не тестировалось независимо от перезапуска publisher/MediaMTX. |
| Recovery из состояния после host reboot | **UNKNOWN / NOT RUN** | После загрузки изолированная PostgreSQL была остановлена, camera-service перезапускался; post-boot playback не проверялся. |

## Playwright browser E2E

| Сценарий | Статус | Зафиксированный результат |
|---|---|---|
| `frontend` — `npm run test:broadcast:e2e` (Chromium, Playwright) | **PASSED** | Страница watch и защищённый iframe декодировали H.264 640×360; воспроизведение продвигалось через 90-секундный интервал обновления токена; старый токен получил 401; disable показал offline и запретил обновление токена; FFmpeg PID остался прежним; утечки токена в Referer/проверенных логах не обнаружены. |
| Четыре одновременных браузерных viewers | **PASSED** | В составе браузерного прогона; отдельный HTTP load результат ниже. |

## Нагрузочные и concurrency сценарии

| Сценарий | Статус | Измеренный/зафиксированный результат |
|---|---|---|
| 4 viewers, HTTP load, 30.23 секунды | **PASSED** | 128 запросов, 0 ошибок, 125080 байт; p50 6.56 ms, p95 11.04 ms, throughput 4.04 KiB/s. CPU/RAM именно финального запуска не записаны. |
| Live batches 32 / 33 / 96 сегментных запросов | **COMPLETED, INCONCLUSIVE** | Последние статусы: 32×200, 33×200, 96×200; предыдущие перемешанные 200/429 не доказывают насыщение semaphore. |
| `BroadcastPlaybackControllerTest.capsConcurrentUpstreamRequestsAt32AndReleasesSlotsAfterCompletion` | **PASSED** | Детерминированный unit-тест удерживал 32 upstream вызова, отклонял следующий запрос и принимал новый после освобождения слота. Запущен один фильтрованный метод; Gradle 8.14.2, один worker, без parallel; BUILD SUCCESSFUL, 1m25s. Этот сценарий уже выполнен и здесь не повторялся. |

## Почему 55, 78 и 23 — разные числа

Это арифметика более раннего снимка реестра, а не текущий подсчёт. Тогда в исходниках было **78 Java test methods**, а сохранённый прежний stream-service suite содержал **23 PASS**. Разность `78 − 23 = 55` обозначала все остальные исходные методы, не количество запусков и не число PASS. Затем Phase 3.2A добавила методы и довела снимок до 98; Phase 3.2B добавила ещё 17, поэтому сейчас в исходниках 115 методов.

На снимке после Phase 2.1 весь реестр оценивался как 56 PASS и 22 NOT RUN. В историческом Phase 3.2B снимке сводка составляла **96 PASSED, 0 FAILED, 0 SKIPPED, 22 NOT RUN**; это не актуальный сводный пересчёт после Phase 3.3A/3.3B. В текущей Camera module table добавлен один новый подтверждённый метод ниже. Не суммируйте этот старый агрегат с последующими фазовыми таблицами без повторного полного inventory-подсчёта.

## Источники и ограничения

- Исходники: `*/src/test/java/**/*.java` в gateway, common и сервисных модулях.
- Текущие XML: `*/build/test-results/test/TEST-*.xml`; точечные запуски Phase 3.2B заменили результаты XML в изменённых модулях, поэтому полный текущий per-test XML снимок недоступен. Статусы из запусков сохранены выше; исторические результаты помечены отдельно.
- Исторический прогресс и отдельные интеграционные/браузерные/нагрузочные результаты описаны в `docs/broadcast-test-progress.md` и `docs/broadcast-integration-tests.md`. Их утверждения здесь приведены как сохранённые результаты, повторной проверки не было.
- `NOT RUN` в таблицах отражает только отсутствие надёжного результата в просмотренных артефактах.

## Phase 3.3A — Organization Management focused validation (2026-11-24)

Current source test inventory gains two methods in `OrganizationControllerSecurityTest`, three methods in `OrganizationRolePolicyTest`, one method in `OrganizationManagementServiceTest`, and one method in `LegacySecurityConfigurationTest`. The previously recorded 153-method source snapshot therefore becomes 160 `@Test` methods, without counting parameterized invocations or assertions. These are new/currently affected source methods and are **not** part of historical Phase 3.2 totals above.

| Full test method | Current result | Evidence |
|---|---|---|
| `com.coltrack.identityservice.controller.OrganizationControllerSecurityTest.rejectsAnonymousOrganizationCreation` | **PASSED** | Filtered `:services:identity-service:test`; HTTP POST to organization creation returned 401 without JWT. |
| `com.coltrack.identityservice.controller.OrganizationControllerSecurityTest.createsWithTheValidatedOidcPrincipalAndNoStoreResponse` | **PASSED** | Same filtered test task; verified issuer, subject, email and `email_verified` principal extraction, 201 and `Cache-Control: no-store`. |
| `com.coltrack.identityservice.service.OrganizationRolePolicyTest.adminCanOnlyManageOperatorAndViewerMemberships` | **PASSED** | Exact filtered `:services:identity-service:test`; verifies ADMIN cannot manage ADMIN/OWNER or assign elevated roles. |
| `com.coltrack.identityservice.service.OrganizationRolePolicyTest.adminCanInviteOnlyOperatorAndViewer` | **PASSED** | Same exact filtered class; ADMIN cannot invite ADMIN/OWNER. |
| `com.coltrack.identityservice.service.OrganizationRolePolicyTest.ownerCanManageAndInviteAllSupportedRoles` | **PASSED** | Same exact filtered class; verifies OWNER policy across all fixed roles. |
| `com.coltrack.identityservice.service.OrganizationManagementServiceTest.lastActiveOwnerCannotBeRemoved` | **PASSED** | Exact filtered service test; mocked persistence returns one active OWNER, service returns 409 and does not revoke membership. This is unit evidence only, not a concurrency or PostgreSQL trigger test. |
| `com.coltrack.gateway.security.LegacySecurityConfigurationTest.deniesSaaSOrganizationRoutesInLegacySurveillanceMode` | **PASSED** | Filtered `:gateway:camera-gateway:test`; both new SaaS paths returned 401 anonymously, while the same class confirmed existing unauthenticated Camera routing still returns 200. |

Commands were run separately:

```bash
./gradlew --no-daemon --no-parallel --max-workers=1 :services:identity-service:test --tests 'com.coltrack.identityservice.controller.OrganizationControllerSecurityTest'
./gradlew --no-daemon --no-parallel --max-workers=1 :services:identity-service:test --tests 'com.coltrack.identityservice.service.OrganizationRolePolicyTest'
./gradlew --no-daemon --no-parallel --max-workers=1 :services:identity-service:test --tests 'com.coltrack.identityservice.service.OrganizationManagementServiceTest'
./gradlew --no-daemon --no-parallel --max-workers=1 :gateway:camera-gateway:test --tests 'com.coltrack.gateway.security.LegacySecurityConfigurationTest'
```

Successful JUnit reports were inspected immediately: controller class had 2 tests, policy class had 3, service class had 1, and legacy Gateway class had 2; each final report had 0 failures/errors/skips. Later filtered invocations replace earlier XML on disk. An initial identity attempt failed during Java compilation on two implementation errors before tests executed; both errors were corrected. The first Gateway assertion expected 403, while Spring Security returned 401 for anonymous `denyAll`; the expectation was corrected and the focused class passed. No unrelated test was repeated. PostgreSQL V2 migration/trigger behavior, concurrent last-OWNER changes, invitation persistence/replay/expiry, and Surveillance runtime containers remain **NOT RUN**; there is no isolated database validation evidence for this phase yet.

## Phase 3.3A.1 — Identity Persistence & Concurrency Acceptance (2026-11-24)

**Overall: PASS on the declared PostgreSQL 16.13 target `identity_test_db`.** Read-only preflight verified database `identity_test_db`, role/owner `identity_test_user`, endpoint `127.0.0.1:5432`, PostgreSQL 16.13, empty `identity` schema and absent Flyway history. Disk/memory before the focused integration run: 19 GiB free and 23 GiB available. Older PostgreSQL 17 runs recorded elsewhere are historical evidence, not a target-version requirement.

The current source inventory is 171 JUnit `@Test` methods at this snapshot: the Phase 3.3A count of 160 plus these 11 database acceptance methods. This is a method count; assertions are not counted as tests.

Migration evidence: Identity Service Flyway startup with target version 1 applied V1; one synthetic V1 tenant/user/active-OWNER fixture was inserted and read back. Normal Identity Service startup then applied V2. Flyway history shows schema creation, V1 and V2 successful, zero failed migrations. The V1 fixture's tenant, user identity/email and active OWNER membership remained unchanged after upgrade. Catalog inspection confirmed invitation table constraints, token-hash and pending-email uniqueness, expiry/list indexes, membership role index and two `DEFERRABLE INITIALLY DEFERRED` OWNER constraint triggers. No ownerless tenants or temporary test helper functions remained after test execution.

Focused execution (one module task, one class filter, one worker):

```bash
IDENTITY_ACCEPTANCE_TARGET=identity_test_db \
IDENTITY_ACCEPTANCE_JDBC_URL=jdbc:postgresql://127.0.0.1:5432/identity_test_db \
IDENTITY_ACCEPTANCE_DB_USER=identity_test_user \
./gradlew --no-daemon --no-parallel --max-workers=1 \
  :services:identity-service:test \
  --tests 'com.coltrack.identityservice.service.IdentityPersistenceAcceptanceTest'
```

Final JUnit XML: `IdentityPersistenceAcceptanceTest`, 11 tests, **11 passed, 0 failed, 0 errors, 0 skipped**. The class is opt-in and asserts the exact database, role and PostgreSQL 16.13 version before each test. It is safe only when the test target variables are deliberately set; test-created tenant/user/membership/invitation rows are retained in this dedicated database. Final counts: 28 tenants, 14 users, 38 memberships and 8 invitations. All are test data; the DB was empty before V1 setup except for the fixture inserted by this acceptance.

| Test method | Status | Verification |
|---|---|---|
| `com.coltrack.identityservice.service.IdentityPersistenceAcceptanceTest.flywayV1UpgradeToV2PreservesExistingSyntheticTenantAndMembership` | **PASSED** | V1/V2 history and original pre-V2 synthetic membership preserved. |
| `com.coltrack.identityservice.service.IdentityPersistenceAcceptanceTest.organizationAndFirstOwnerCommitTogetherAndTenantRenameIsScoped` | **PASSED** | First OWNER committed with tenant; rename and membership resolution work; cross-tenant get is 404. |
| `com.coltrack.identityservice.service.IdentityPersistenceAcceptanceTest.databaseRejectsCommittedTenantWithoutActiveOwner` | **PASSED** | Deferred constraint rejected commit with SQLSTATE 23514; tenant absent afterward. |
| `com.coltrack.identityservice.service.IdentityPersistenceAcceptanceTest.ownerInsertFailureRollsBackOrganizationCreation` | **PASSED** | Synthetic OWNER insert trigger failed; service transaction rolled back tenant/user creation. |
| `com.coltrack.identityservice.service.IdentityPersistenceAcceptanceTest.duplicateSlugReturnsConflictAndDoesNotCreateSecondTenant` | **PASSED** | Duplicate returns 409; one tenant remains for that slug. |
| `com.coltrack.identityservice.service.IdentityPersistenceAcceptanceTest.deferredConstraintRejectsRevokingLastOwnerDirectly` | **PASSED** | Direct SQL status revocation failed at commit with SQLSTATE 23514; OWNER remains active. |
| `com.coltrack.identityservice.service.IdentityPersistenceAcceptanceTest.concurrentRemovalOfTwoOwnersCannotRemoveBoth` | **PASSED** | Separate concurrent transactions: one removal succeeded, the other was rejected; at least one active OWNER remained. |
| `com.coltrack.identityservice.service.IdentityPersistenceAcceptanceTest.concurrentOwnerSelfDemotionCannotRemoveBothOwners` | **PASSED** | Separate concurrent transactions: one self-demotion succeeded, the conflicting operation was rejected; OWNER remained. |
| `com.coltrack.identityservice.service.IdentityPersistenceAcceptanceTest.invitationStoresOnlyHashRequiresVerifiedMatchingEmailAndRejectsReplay` | **PASSED** | Stored 32-byte SHA-256 matched raw token hash; missing, unverified and mismatched email denied; valid accept succeeded; replay returned 410. |
| `com.coltrack.identityservice.service.IdentityPersistenceAcceptanceTest.concurrentAcceptanceIsSingleUseAndMembershipRevocationIsImmediate` | **PASSED** | Concurrent accepts yielded exactly one success/one 410; cross-tenant invitation list returned 404; revocation removed next membership resolution. |
| `com.coltrack.identityservice.service.IdentityPersistenceAcceptanceTest.expiredAndRevokedInvitationsAreRejected` | **PASSED** | Expired invitation became EXPIRED and returned 410; revoked token returned 410. |

The first integration attempt recorded 11 test-method context-load errors before the bodies ran because the mock web context omitted `HttpSecurity`; no DB assertions/writes occurred. The next attempt had 8 method failures: invitation inserts exposed a real JDBC bug (PostgreSQL could not infer a type for `Instant` in expiry), and test cleanup conflicted with the deferred OWNER trigger. Expiry binding now uses `Timestamp.from(Instant)`. Test rows are retained instead of disabling or bypassing the invariant for cleanup. After fixes, the exact filtered class passed; it was run once more after adding the missing-email assertion, and the final XML above is authoritative. The original mocked unit, Broadcast, Kafka/STOMP, browser and HLS suites were not repeated.

PostgreSQL 16.13 is the current target and its focused acceptance passed. PostgreSQL 17 is not an acceptance requirement for Phase 3.3A or later.

## Phase 3.3B — Vue Streaming SaaS Dashboard

| Check | Status | Evidence |
|---|---|---|
| `frontend` `npm run build` | **PASSED** | Vue `vue-tsc -b` passed and Vite 8.3.8 generated the production bundle after adding SaaS routes, API client, tenant selection handling and PRIVATE player authorization retry. Vite emitted a chunk-size advisory for the main chunk (~630 kB minified); build succeeded. |
| Dashboard browser E2E (snapshot before Phase 3.3B.1 live run) | **NOT RUN at that time** | Historical status only; superseded by the later isolated browser run below. |

No Java tests, Docker, database migration, Surveillance operations or regression/browser suites were run for this phase. The build is static/type validation only and does not establish live API compatibility or playback behavior. See `docs/saas/SAAS_DASHBOARD.md` for remaining browser scenarios.

## Phase 3.3B.1 — SaaS Dashboard browser acceptance: initial blocked preflight (2026-11-24)

This is a historical preflight record. The later isolated live acceptance below supersedes its blocked status; the outcomes are preserved to show that no service was started before the initial Docker/volume safety review.

**Overall status: BLOCKED. No real Chromium/OIDC/API browser flow was observed.** Exact environment evidence:

- Preflight: 19 GiB free disk; 23 GiB RAM available.
- `psql --version`: PostgreSQL client 16.13.
- `pg_isready -h 127.0.0.1 -p 5432`: no response; no database writes/migrations occurred.
- Read-only Docker inspection (`docker ps -a`, images, volumes, `docker system df`): Docker daemon unavailable/not running. Existing container, image and volume inventories could not be obtained. The daemon was not started.
- Playwright Chromium: found in `/home/tatarino/.cache/ms-playwright/chromium-1255/chrome-linux64/chrome`; launched successfully for a local legacy frontend routing smoke check. No browser was installed.
- Static validation: `docker compose -p surveillance-demo -f docker-compose.demo.yml config -q` **PASS**. Streaming `docker compose -p dashboard-it -f docker-compose.streaming.yml config -q` **PASS** with dummy validation-only values for required variables. These commands did not start services and do not verify runtime DB-volume isolation.

| Scenario | Status | Evidence |
|---|---|---|
| Verify existing Docker resources and PostgreSQL volume major versions | **BLOCKED** | Daemon unavailable; no container/volume inspection possible. No PostgreSQL volume was mounted. |
| Isolated PostgreSQL 16.13 startup and tenant fixture | **BLOCKED** | No local server response; no database created or modified. |
| OIDC PKCE login, callback, app return, reload, logout, expiry, anonymous route denial | **BLOCKED** | No live Keycloak/Gateway; browser cannot complete OIDC without those services. |
| Organization create/OWNER, switching, rename, multiple organizations and state isolation | **BLOCKED** | No isolated Identity API/DB or browser. |
| Invitation issue/copy, matching/mismatched verified email, role change/removal, last OWNER, revoke/expiry | **BLOCKED** | No synthetic users or membership records created. |
| Camera create/list/edit and credential non-disclosure | **BLOCKED** | No Camera API or browser session. |
| Broadcast create, visibility, enable/disable, watch URL and iframe | **BLOCKED** | No Stream API or browser session. |
| PRIVATE anon denial, authorized playback, cross-tenant denial and revocation renewal | **BLOCKED** | No playback API or synthetic HLS upstream invoked. |
| PUBLIC anonymous playback in the new UI | **BLOCKED** | Prior public-player result is historical and not evidence for this integration. Not repeated. |
| Legacy frontend route smoke (`VITE_AUTH_MODE=legacy`) | **PASSED (frontend only)** | Local build + loopback Vite preview in Chromium: `/app` redirected to `/`, SaaS shell absent, `/watch/{syntheticId}` remained accessible and rendered one video element. API calls were blocked by the test; playback was not asserted. |
| Full Surveillance deployment compatibility | **BLOCKED (runtime)** | Demo Compose config passed, but daemon unavailable; no deployment container was started. |

No screenshot was saved during this preflight. Unrelated Broadcast E2E, Gradle regression, Kafka/STOMP and load tests were deliberately not run.

## Phase 3.3B.1 — SaaS Dashboard browser acceptance: isolated live run (2026-11-24)

**Status: PARTIAL PASS; Phase 3.3B.1 remains open.** Environment: Compose project `phase33b1-saas-acceptance`; isolated PostgreSQL 16.13, Keycloak 26.8.2 and Kafka 3.9.1 containers with fresh project-scoped volumes; Identity/Camera/Stream/Gateway JARs, Vite and synthetic HLS server on loopback. Only four containers in that project were running. No Surveillance, `broadcast-it` or `phase32d` data/containers/volumes were modified. Disk/RAM preflight was 18 GiB free and 20 GiB available at resume. See `docs/saas/SAAS_DASHBOARD.md` for ports, exact evidence and limitations. Current Java source inventory at this snapshot contains 172 test methods; browser scenarios and assertions are reported separately below.

| Scenario (browser scenario count; not JUnit method count) | Status | Evidence |
|---|---|---|
| Authorization Code + PKCE, callback, protected routes, reload persistence, logout | **PASSED** | Chromium completed synthetic OWNER/VIEWER login; `/app` callback and reload remained authenticated; no access token in localStorage; logout returned outside `/app`. Session expiry was not tested. |
| Organization create/OWNER, rename, second organization/switch and clearing tenant state | **PASSED** | Synthetic OWNER managed two organizations; switching did not retain previous tenant Camera/Broadcast collections. |
| Invitations, verified email, expiration, revocation, single use | **PASSED** | Matching verified email accepted (200); mismatch (403); expired/revoked (410); valid link was single-use. |
| Role changes, member removal, last OWNER invariant | **PASSED** | VIEWER↔OPERATOR, removal; last OWNER demotion/leave returned 409. |
| Camera create/edit and credential secrecy | **PASSED** | Synthetic Camera UI/API did not reveal RTSP credentials. Related focused `CameraCredentialsTest.resolvesStandardUrlWhenOptionalUsernameIsMissing()` was 1/1 PASS; JUnit XML had 0 failures/errors/skips. |
| Broadcast create/state/visibility/permanent watch and iframe | **PASSED** | Create and enable/disable/re-enable worked; PUBLIC/PRIVATE changes worked; stable URL had no playback grant, iframe HTML was copied and contained no grant. Plain watch URL clipboard copy was not checked. |
| PUBLIC anonymous HLS and iframe | **PASSED** | Chromium decoded synthetic H.264 from anonymous PUBLIC playback and from the PUBLIC iframe. |
| PRIVATE anonymous denial, authenticated playback and iframe | **PASSED** | Anonymous token request 401; active member's HLS decoded synthetic H.264; authorized PRIVATE iframe decoded. |
| Cross-tenant Camera/Broadcast/playback | **PASSED (denied)** | Camera 403; Broadcast 404; PRIVATE grant for the other selected tenant 401. Detailed comparison of Camera response body with a random unknown UUID was not separately asserted. |
| Membership revocation during playback | **PASSED** | Subsequent grant renewal 401; fresh player request rendered private/unavailable status. Existing already-downloaded segments are not retroactively revoked. |
| Responsive viewport, OIDC session expiry, long-duration playback-token renewal | **NOT RUN** | No viewport/device matrix or timed expiry test in this run. |
| Direct PRIVATE watch before prior OIDC sign-in | **NOT RUN** | Tested flow signs into `/app` then opens the permanent player URL; fresh anonymous in-player login prompting remains unverified. |
| Invitation URL clipboard copy and plain watch URL clipboard copy | **NOT RUN** | The invitation acceptance used a one-time URL from the OWNER creation response/status; permanent URL was inspected and iframe HTML copied, but these two plain-link clipboard actions were not separately clicked/asserted. |
| Full Surveillance runtime compatibility | **NOT RUN** | Existing Surveillance resources were deliberately left untouched; only previous static/legacy route smoke is historical evidence. |

**Relevant defect/fix:** During private playback, anonymous grant returned 401 and Keycloak `check-sso` callback redirected from the player to `/`, where router navigation moved to `/app`. `frontend/src/auth/oidc.ts` now preserves `/watch/{publicId}` plus its tenant selector as the initialization callback only on watch routes. Chromium then completed authorized PRIVATE grant and video decoding without leaving the player.

**Executed current browser command:** from `frontend/`, with synthetic environment loaded from the permission-restricted `/tmp/phase33b1.env`:

```bash
DASHBOARD_RESUME_FROM_BROADCAST=true \
DASHBOARD_RESUME_FROM_PRIVATE=true \
DASHBOARD_RESUME_AFTER_PRIVATE_GRANT=true \
node tests/saas-dashboard-browser-acceptance-resume.mjs
```

It reported 5 resumed scenario passes: reusing recorded authorized HLS/cross-tenant evidence, anonymous PRIVATE denial, PRIVATE iframe decoding, member role/removal/revocation and logout. A preceding targeted run actually decoded PRIVATE HLS and issued the cross-tenant calls; it stopped because the harness expected Camera 404 while the API returned 403. The harness expectation was changed to accept the API's 403 or 404 denial. The already successful private HLS decode and preceding cross-tenant calls were not repeated after that correction. The failed check was a test expectation mismatch, not an authorization allow.

`npm run build` passed after the watch callback correction (Vue typecheck + Vite production build; main chunk warning ~630 kB). `node --check` and `git diff --check` passed. No Java regression, full Broadcast E2E, Kafka/STOMP, load test, full RTSP/MediaMTX suite or Surveillance operation was run. Historical PASS tests in other phases were not repeated.

Source inventory, executed browser scenarios and assertions are separate counts: the frontend acceptance script is not a JUnit test method, and the video-width/progress assertions are not additional tests. No screenshots were saved. This was the Phase 3.3B.1 checkpoint; the remaining checks and final decision are recorded below.

## Phase 3.3B.2 — Final SaaS Dashboard acceptance (2026-11-24)

**Status: PASS; Phase 3.3B is closed for the agreed MVP browser acceptance scope.** The existing `phase33b1-saas-acceptance` project was checked read-only and reused. Its two PostgreSQL servers reported 16.13; Keycloak reported 26.8.2. Four project containers were already Up, all published ports were loopback-only. No Docker lifecycle operation, image build, Surveillance/broadcast-it/phase32d operation, or production database operation was performed. Disk/RAM preflight: 18 GiB free, 20 GiB available. Host application services were reused on loopback.

### OIDC access-token expiry/session cases

| Scenario | Status | Exact observed result |
|---|---|---|
| Valid session refresh after actual access-token expiry | **PASSED** | Isolated Keycloak realm lifespan temporarily 45 seconds; captured token lifetime was <=47s. After `exp`, Camera refresh returned 200 with a changed bearer token. |
| Logout and `/app` protection | **PASSED** | Logout invalidated the browser session; `/app` showed one Keycloak login prompt and no SaaS shell, without redirect loop. |
| Expired token with server-side session revoked | **PASSED** | Synthetic user session revoked using isolated Keycloak admin API; after token expiry, management requests were rejected/aborted, no replacement token appeared, and browser stayed at one login prompt. No management success response was observed. |
| Isolated Keycloak test setting restoration | **PASSED (verified separately)** | First run's admin token expired before cleanup, so the runner exited nonzero after scenario assertions. Fresh admin authentication restored lifespan to 300s; a follow-up realm GET confirmed 300s. The test helper now refreshes its admin token in cleanup; assertions were not repeated. |

### Playback authorization renewal

| Scenario | Status | Exact observed result |
|---|---|---|
| PRIVATE HLS continues across grant renewal | **PASSED** | Synthetic HLS: 70 segments / 140s, 320×180 H.264; grant configuration PT90S. Chromium progressed past 40s with two distinct grants; observed protected media responses were HTTP 200. |
| Old grant rejected after actual expiration | **PASSED** | At the first grant's actual expiry, Gateway request to its protected playlist URL returned HTTP 401. Current playback continued beyond 80s. |
| Permanent watch/iframe URLs contain no playback grant | **PASSED (reused)** | Prior browser clipboard evidence already checked the stable watch URL and iframe. No grant/credential was present; not repeated. |

The short VOD was generated with one FFmpeg thread in `/tmp`; the original synthetic HTTP fixture playlist was restored and verified by the test's `finally`. This was not an RTSP ingest, MediaMTX suite or load test. Tokens were held in memory and never printed.

### Responsive layouts and clipboard

| Viewport/scenario | Status | Evidence |
|---|---|---|
| 1440×900 desktop: Overview, Cameras, Broadcasts, Members, playback | **PASSED** | No document horizontal overflow; organization switcher visible. |
| 768×1024 tablet: Overview, Cameras, Broadcasts, Members, playback | **PASSED** | No document horizontal overflow; organization switcher visible. |
| 390×844 mobile: Overview, Cameras, Broadcasts, Members, playback | **PASSED** | No document horizontal overflow; tables scroll within their wrappers. |
| Copy permanent PRIVATE watch URL | **PASSED** | Actual browser clipboard held stable path plus tenant selector, no JWT/grant/RTSP credential. |
| Copy invitation link | **PASSED** | Actual clipboard held one-time synthetic invite URL with no JWT/media grant/internal credential; invitation was revoked after checking. |
| Copy iframe HTML | **PASSED (reused)** | Actual clipboard and no-grant/referrer-policy assertion passed during Phase 3.3B.1; deliberately not repeated. |

The test initially found mobile overflow: workspace selector intrinsic width expanded the sidebar and Camera form grid expanded the page. `StreamingApp.vue` now constrains the mobile sidebar and selector, and the shared stack grid uses a shrinkable single column. Post-fix targeted viewport runs passed; desktop sections affected by the shared grid and tablet breakpoint sections were rechecked. Mobile tables retain an internal horizontal scroller. Members uses inline role selection and native browser `window.confirm`, not a custom dashboard modal.

### Legacy Surveillance frontend configuration

**PASS (frontend routes only).** A separate Vite instance on `127.0.0.1:5175` was launched with `VITE_AUTH_MODE=legacy`. Chromium verified the root Surveillance shell, `/app` redirect to `/` without SaaS shell, zero Keycloak requests, and public `/watch/{syntheticId}` route with a video element. Gateway requests were blocked by the test; this is not backend or media playback evidence. No Surveillance container was started.

### Commands and reporting boundaries

Focused browser commands, from `frontend/` with synthetic variables sourced from the protected `/tmp/phase33b1.env`:

```bash
node tests/saas-dashboard-session-acceptance.mjs
node tests/saas-dashboard-playback-renewal.mjs
node tests/saas-dashboard-final-acceptance.mjs
DASHBOARD_LAYOUT_ONLY=true DASHBOARD_VIEWPORT=mobile DASHBOARD_SECTION_ONLY=cameras node tests/saas-dashboard-final-acceptance.mjs
DASHBOARD_LAYOUT_ONLY=true DASHBOARD_VIEWPORT=mobile DASHBOARD_SECTION_ONLY=broadcasts,members node tests/saas-dashboard-final-acceptance.mjs
DASHBOARD_LAYOUT_ONLY=true DASHBOARD_VIEWPORT=desktop,tablet DASHBOARD_SECTION_ONLY=cameras,broadcasts,members node tests/saas-dashboard-final-acceptance.mjs
DASHBOARD_LAYOUT_ONLY=true DASHBOARD_VIEWPORT=tablet DASHBOARD_SECTION_ONLY=overview node tests/saas-dashboard-final-acceptance.mjs
node tests/saas-dashboard-legacy-compat.mjs
```

Some targeted layout runs were split to avoid repeating already passed viewport sections after the CSS correction. One earlier clipboard run failed because the harness read clipboard before async copy completed; it was corrected to wait for UI confirmation and then passed. One early legacy harness run accidentally aborted `/src/api/*.ts` through an overly broad route glob; the glob was removed and the final legacy smoke passed. These were harness defects, not product test failures.

The first playback-renewal attempts also stopped before acceptance assertions: one login helper checked for the OIDC redirect too early, and one `waitForFunction` timeout was passed in Playwright's argument position instead of its options position. The helper and timeout signature were corrected; the final run reached playback, observed renewed grants and verified expired-grant rejection. The synthetic fixture playlist was restored in every run's `finally`.

`npm run build` passed after UI/CSS changes (Vue typecheck + Vite; ~630 kB main-chunk advisory remains). `node --check` for the focused browser scripts and `git diff --check` passed. The OIDC session scenario itself passed, but its first runner exit status was nonzero only because the 300-second admin token expired before realm restoration; the isolated setting was then independently restored and verified. The helper was fixed to reacquire an admin token during cleanup. This cleanup-only correction did not change scenario behavior and the PASS cases were not repeated.

Tests deliberately not repeated: existing Java regression, Broadcast E2E, MediaMTX authentication, Kafka/STOMP, Flyway, full RTSP/ingest integration, load/stress tests, and previously passed Phase 3.3B.1 OIDC/org/membership/Camera/Broadcast/PUBLIC/PRIVATE iframe scenarios. Browser scenario counts are separate from source JUnit methods and assertions; no screenshots were saved.

### Closure and known limits

All five Phase 3.3B.2 scenario groups passed. The signed-out direct PRIVATE watch route does not show an in-player login control; viewers sign in through `/app` before reopening that permanent link. This is deferred UX and remains fail-closed. Full Surveillance backend/media runtime was not started; only its frontend configuration/routing was verified. Public ingress remains disabled. At this 2026-11-24 checkpoint, Phase 3.3C had not started.
## Phase 3.3C.1 — Usage and quota foundation

New focused tests added:

- `OrganizationQuotaServiceTest.returnsEffectivePlanAndOverridesOnlyForActiveMember` — verifies tenant-scoped effective plan/override resolution.
- `OrganizationQuotaServiceTest.doesNotRevealQuotaForNonMember` — verifies inaccessible organizations return 404 without querying quota data.
- `OrganizationQuotaServiceTest.rejectsMissingAuthenticatedPrincipal` — verifies absent identity is rejected.
- `OrganizationQuotaServiceTest.failsClosedWhenTheConfiguredPlanCannotBeResolved` — verifies unavailable plan configuration returns 503.
- `OrganizationQuotaControllerSecurityTest.rejectsAnonymousQuotaRead` — verifies the quota endpoint is authenticated.
- `OrganizationQuotaControllerSecurityTest.returnsNoStoreEffectiveQuotasForAuthenticatedMember` — verifies response fields, no-store policy and validated principal delegation.
- `OrganizationCreationQuotaAssignmentTest.assignsConfiguredDefaultPlanDuringOrganizationCreation` — verifies the configured default plan is assigned during the organization creation service transaction.

Focused execution results (current run):

| Module task | Test methods executed | Result |
|---|---:|---|
| `:services:identity-service:test --tests 'com.coltrack.identityservice.service.OrganizationQuotaServiceTest'` | 3 | **3 PASSED**, 0 failed, 0 skipped; verified from the matching JUnit XML report. |
| `:services:identity-service:test --tests 'com.coltrack.identityservice.controller.OrganizationQuotaControllerSecurityTest'` | 2 | **2 PASSED**, 0 failed, 0 skipped; verified from the matching JUnit XML report. |
| `:services:identity-service:test --tests 'com.coltrack.identityservice.service.OrganizationCreationQuotaAssignmentTest.assignsConfiguredDefaultPlanDuringOrganizationCreation'` | 1 distinct method | **PASS on corrected run**; initial run failed in test-only Mockito vararg capture, then the fixture was corrected and this one method passed. Final matching JUnit XML: 1 test, 0 failures/errors/skips. |
| Repeat `OrganizationQuotaServiceTest` after adding fail-closed 503 behavior | 4 | **4 PASSED**, 0 failed, 0 skipped; this class was rerun because its service behavior changed. |

Source methods: 7. Distinct methods with a final passing result: 7. Test attempts: 11 (the service class ran once for 3 methods and again for 4 after its behavior changed; the controller class ran once for 2 methods; the default-assignment method ran twice because its first test fixture failed and was corrected). Ten attempts passed and one initial test-fixture attempt failed; no product-code test remains failing. No parameterized invocations or assertion counts are included. At this initial implementation checkpoint, Identity Flyway V3 had not yet been applied; the later Phase 3.3C.1B database acceptance follows. No previous OIDC, HLS, Broadcast, Kafka/STOMP, load, or Surveillance tests were selected or repeated. Usage aggregation and quota enforcement remain NOT RUN.

## Phase 3.3C.1B — Identity quota PostgreSQL acceptance (2026-11-25)

Test executed: `IdentityQuotaPersistenceAcceptanceTest` in `:services:identity-service:test`, filtered to that class and run with `--no-daemon --no-parallel --max-workers=1` against a new isolated PostgreSQL 16.13 cluster. The exact acceptance database and role were checked in every test setup. Flyway V1, V2 and V3 were applied sequentially to the fresh schema. A second fresh database was migrated to V2, seeded with a synthetic tenant, user and OWNER membership, then upgraded to V3.

| Test method | Result | Evidence |
|---|---|---|
| `freshFlywayAppliesV1ThroughV3AndSeedsConstrainedPlanCatalog` | **PASS** | SQL migration history V1/V2/V3 successful; catalog values FREE 2/1/3, PRO 10/5/25, BUSINESS 50/25/200; 8 quota check constraints; existing deferred active-OWNER triggers remain. |
| `organizationCreationAssignsDefaultAndLegacyOrganizationFallsBackWithoutBackfill` | **PASS** | New organization assigned FREE with one ACTIVE OWNER; removing the explicit assignment resolved the configured default without recreating/backfilling it. |
| `effectiveQuotaUsesPlanAndNullablePerMetricOverrides` | **PASS** | PRO limits resolved and only `max_cameras` override changed the effective limit; other override fields remained SQL NULL. |
| `organizationRollbackLeavesNoQuotaAssignmentAndOwnerInvariantIntact` | **PASS** | Synthetic OWNER insert trigger forced rollback; tenant and quota assignment were both absent afterward. |
| `unknownPlanAndMissingConfiguredDefaultFailClosedAndMembershipIsRequired` | **PASS** | Unknown plan rejected by FK; invalid configured fallback returned 503; non-member received 404. |
| `quotaHttpResponseIsAuthenticatedMemberScopedAndNotCacheable` | **PASS** | Anonymous request 401; active member received 200, `Cache-Control: no-store`, and effective limits; unknown subject received 404. |
| `v2SchemaUpgradeToV3PreservesSyntheticTenantAndDoesNotAssignPlan` | **PASS** | Flyway V2→V3 successful; synthetic tenant and OWNER membership preserved; no plan assignment or overrides backfilled. |

JUnit XML: 7 tests, 0 failures, 0 errors, 0 skipped. Two earlier clean-scratch attempts found the missing SQL bind parameter and exposed test assumptions about Flyway's schema-creation history row and read-only user discovery; fixes were made and the final run used a third, fresh cluster. Those initial attempts are not counted as passing results. Previously passing quota unit/controller tests and unrelated OIDC/HLS/Broadcast/Kafka/STOMP/browser/load suites were not repeated.

**Phase 3.3C.1 is closed for its foundation and PostgreSQL persistence acceptance.** Camera/Broadcast quota enforcement, authoritative usage aggregation, viewer sessions, and production rollout are not part of this closure.

## Phase 3.3C.2 — Camera and Broadcast quota enforcement (2026-11-25)

This phase adds 16 Java source test methods: 2 in `common-security`, 11 in Camera Service (2 admission unit tests, 7 PostgreSQL quota acceptance tests, 1 Flyway test, 1 usage controller test), and 3 Stream Service tests (2 lifecycle tests and 1 usage controller test). Three existing controller methods were directly updated for tenant-scoped usage and Camera idempotency handling. Exact method results follow; these are method counts, not assertions or parameterized invocations.

| Full test method | Status | Evidence |
|---|---|---|
| `com.coltrack.identity.quota.TenantQuotaGuardTest.rejectsAtLimitAfterAcquiringOrganizationLock` | **PASS** | Matching JUnit XML: 1 test, 0 failures/errors/skips; verifies machine-readable rejection after the tenant-scoped transaction lock. |
| `com.coltrack.identity.quota.TenantQuotaGuardTest.databaseLookupFailureFailsClosed` | **PASS** | Matching JUnit XML; simulated Identity/quota-schema JDBC failure returns the fail-closed exception before resource count. |
| `com.coltrack.cameraservice.service.CameraQuotaAdmissionTest.repeatedCreateWithSameKeyAndPayloadReturnsTheSameCameraWithoutSecondAdmission` | **PASS** | Matching JUnit XML: 1 test, 0 failures/errors/skips; same logical create returns prior Camera and makes one quota admission/write. Final Gradle result was restored from cache for this unchanged exact input. |
| `com.coltrack.cameraservice.service.CameraQuotaAdmissionTest.atLimitCameraCreationDoesNotPersist` | **PASS** | Matching JUnit XML: 1 test, 0 failures/errors/skips; checks that quota denial precedes Camera persistence. |
| `com.coltrack.cameraservice.controller.CameraControllerTenantAuthorizationTest.createAssignsTenantFromMembershipContext` | **PASS** | Matching JUnit XML: 1 test, 0 failures/errors/skips; validated tenant and idempotency key are delegated to Camera Service. |
| `com.coltrack.cameraservice.controller.CameraControllerTenantAuthorizationTest.cameraUsageIsMembershipScopedAndNotCacheable` | **PASS** | Matching JUnit XML; active tenant Viewer receives usage with `Cache-Control: no-store`. |
| `com.coltrack.cameraservice.controller.CameraControllerTenantAuthorizationTest.absentMembershipDeniesListAndCameraMutationsBeforeAnyLookup` | **PASS** | Directly updated and rerun; usage request is denied with 404 before service access. |
| `com.coltrack.streamservice.controller.BroadcastControllerTenantAuthorizationTest.broadcastUsageIsMembershipScopedAndNotCacheable` | **PASS** | Matching JUnit XML; active tenant Viewer receives usage with `Cache-Control: no-store`. |
| `com.coltrack.streamservice.controller.BroadcastControllerTenantAuthorizationTest.missingMembershipDeniesBroadcastReadDeleteAndPlaybackInfoBeforeServiceCalls` | **PASS** | Directly updated and rerun; usage request is denied with 404 before service access. |
| `com.coltrack.streamservice.service.BroadcastQuotaLifecycleTest.activationReservesBeforePersistingEnabledState` | **PASS** | Matching JUnit XML: 1 test, 0 failures/errors/skips; capacity check precedes desired-state write. |
| `com.coltrack.streamservice.service.BroadcastQuotaLifecycleTest.quotaDenialLeavesBroadcastDisabled` | **PASS** | Matching JUnit XML: 1 test, 0 failures/errors/skips; quota refusal performs no enabled-state update. |
| `com.coltrack.cameraservice.service.TenantQuotaPostgresAcceptanceTest.concurrentCameraCreatesSerializeAtOneRemainingSlot` | **PASS** | Isolated PostgreSQL 16.13; exactly 1 of 2 concurrent admissions succeeded; tenant Camera count ended at 3. |
| `com.coltrack.cameraservice.service.TenantQuotaPostgresAcceptanceTest.concurrentBroadcastActivationsSerializeAtOneRemainingSlot` | **PASS** | Isolated PostgreSQL 16.13; exactly 1 of 2 concurrent admissions succeeded; exactly 1 Broadcast ended enabled. |
| `com.coltrack.cameraservice.service.TenantQuotaPostgresAcceptanceTest.rollbackDoesNotConsumeCameraCapacityAndDeletionReleasesIt` | **PASS** | Isolated PostgreSQL 16.13; rolled-back create left count unchanged and physical deletion freed capacity. |
| `com.coltrack.cameraservice.service.TenantQuotaPostgresAcceptanceTest.loweringQuotaBelowUsagePreservesRowsAndRejectsOnlyFurtherCreation` | **PASS** | Isolated PostgreSQL 16.13; existing two Cameras remained when override was lowered to one; additional create rejected. |
| `com.coltrack.cameraservice.service.TenantQuotaPostgresAcceptanceTest.disablingBroadcastReleasesItsActiveSlot` | **PASS** | Isolated PostgreSQL 16.13; disabled Broadcast released the count and the same Broadcast could then be admitted again. |
| `com.coltrack.cameraservice.service.TenantQuotaPostgresAcceptanceTest.concurrentCameraCreateAndDeleteNeverExceedLimit` | **PASS** | Isolated PostgreSQL 16.13, two transactions; either ordering ended at or below the one-Camera limit. |
| `com.coltrack.cameraservice.service.TenantQuotaPostgresAcceptanceTest.concurrentBroadcastEnableAndDisableNeverExceedLimit` | **PASS** | Isolated PostgreSQL 16.13, two transactions; enabling while another Broadcast disables never exceeded one active row. |
| `com.coltrack.cameraservice.service.CameraQuotaMigrationPostgresAcceptanceTest.cameraV1ThroughV4ApplyAdditivelyAndPreserveLegacyCameraRow` | **PASS** | Flyway V1–V4 plus baseline 0 succeeded in a separate fresh PostgreSQL 16.13 database; synthetic legacy Camera stayed unassigned and V4 idempotency schema existed. |

Database environment: new local PostgreSQL 16.13 cluster under `/tmp`, loopback port 56439; test databases `quota_acceptance_phase33c2` and `quota_acceptance_migrations`. The opt-in acceptance fixtures require a loopback URL and `quota_acceptance_*` DB name. No Docker containers, production/Surveillance/preview databases, volumes or data were used. No Docker service was started.

During implementation, an initial live lock attempt exposed an incorrect `JdbcTemplate` callback assumption (the tenant row was falsely treated as missing). That implementation defect was fixed by switching to an explicit `ResultSetExtractor`; the Camera race then passed. Other initial failed attempts were limited to a test fixture expectation (Mockito assertion), an omitted Flyway baseline version 0 in the expected list, and a compilation error before a test ran. Each directly affected method was corrected and its final passing result is shown above; no final failure remains.

The final test evidence verifies the quota guard transaction pattern, but not concurrent HTTP requests through multiple live service instances, process-crash recovery between Broadcast DB commit and media lease side effect, deployed least-privilege DB role grants, or coordination with out-of-band plan/override edits. Usage is a direct owner-table snapshot, not a reconciled ledger. Cross-tenant API authorization behavior was unchanged and existing historical controller IDOR tests were not repeated. OIDC/HLS, Kafka/STOMP, browser, load/stress and broad regression suites were deliberately NOT RUN.

**Status:** focused implementation and PostgreSQL transaction/migration acceptance **PASS**. Production rollout remains gated on role-grant review, multi-instance HTTP acceptance and crash/reconciliation checks. `MAX_CONCURRENT_VIEWERS` remains **NOT IMPLEMENTED**.

## Phase 3.3C.2B — Resumed runtime acceptance (2026-11-25)

Previously completed Phase 3.3C.2B runtime scenarios remain historical PASS and were deliberately not repeated: multi-instance Camera/Broadcast HTTP concurrency, Camera idempotency/conflicting retry, cross-tenant quota isolation, quota-provider outage, Dashboard indicators/messages, and persisted Broadcast lease reconciliation. Existing Phase 3.3C.2 focused/PostgreSQL results above also remain historical PASS. New checks used isolated PostgreSQL 16.13, synthetic data and loopback-only services under project `phase33c2b-lp-media`. Protected preview, `phase33b1-saas-acceptance`, demo and Surveillance resources were not targeted. Runtime evidence is separate from JUnit source methods and assertions.

### Current runtime results

| Scenario | Result | Recorded outcome |
|---|---|---|
| Fresh PostgreSQL and service startup | **PASS** | Identity V1–V5, Camera V1–V5, Stream V1–V4 applied on PG 16.13. Identity/Camera/Stream connected under distinct runtime usernames; WebSocket has a separate limited role. |
| Synthetic existing-schema upgrade | **PASS** | Clone simulated old shared ownership and pre-security Flyway histories; role bootstrap, ownership transfer and migrations succeeded. Existing counts remained 1 tenant, 2 Cameras, 1 Broadcast. |
| Restricted role flags and grants | **PASS** | All seven runtime/migrator roles have NOSUPERUSER, NOCREATEDB, NOCREATEROLE, NOBYPASSRLS. App roles have no database/schema CREATE. Identity runtime cannot modify resource tables; Camera/Stream are limited to their named owner tables. Runtime access to Flyway histories is revoked. |
| Scoped cross-schema access and quota lock | **PASS** | Camera/Stream have schema USAGE and EXECUTE only on named quota/membership helper functions, not direct Identity table SELECT. Actual runtime connections resolved tenant/quota helpers and acquired `pg_try_advisory_xact_lock`. WebSocket has helper execution only and no table DML. |
| Flyway migration identities and future grants | **PASS** | Migration identities own their service objects. Default ACLs give owned runtime DML on future service tables and USAGE/SELECT on future sequences. Identity helper execution defaults are revoked from runtime and PUBLIC. `identity_migrator` alone has DB CREATE for existing Identity V1; Camera/Stream migrators have `public` schema CREATE for legacy unqualified migrations. |
| RTSP failure, restoration, and HLS recovery | **PASS** | Synthetic source initially returned RTSP 404; Broadcast remained enabled with one lease and agent retried without a PID. After Stream restart and source restoration, agent reached RUNNING; HLS returned 200 and FFprobe decoded H.264 320×180 through the playback route. |
| Reconciliation, deactivation, and orphan check | **PASS** | Desired Broadcast and lease stayed exactly 1/1 across retry/restart. Deactivation cleared desired state and lease, removed media session, and left zero agent/test-source FFmpeg processes. A second available-source activation also reached playable HLS and deactivated cleanly. |

The synthetic upgrade preserved existing fixture counts and current data. Temporary application/media services were stopped after acceptance and the PostgreSQL container was stopped after documentation; isolated PostgreSQL volumes/data were retained. No existing deployment credentials, owners, grants, roles or volumes were altered.

Focused Java checks: `JdbcTenantMembershipResolverTest` executed 2 methods/test cases and `TenantQuotaGuardTest` executed 2 methods/test cases; all 4 passed with zero failures/errors/skips in matching JUnit XML reports. This is not an assertion count. Serial Docker image builds for Identity, Camera, Stream and WebSocket passed; Identity/Camera/Stream were rebuilt after final migrations and passed. No full Gradle suite was run.

**PASS:** Previously completed quota runtime checks retained; new least-privilege, migration, restricted-role quota lock, and live HLS recovery/deactivation checks passed. **FAIL:** none unresolved; corrected harness/setup failures are historical and not product failures. **BLOCKED:** none for C.2B acceptance. **NOT RUN:** full regression, broad browser acceptance, load/stress/soak, public ingress, query/index performance, and coordination with future out-of-band plan/override writers. **Decision:** Phase 3.3C.2B is closed for its acceptance scope. Phase 3.3C.3 remains for persisted logical viewer sessions with atomic multi-instance admission/renewal/expiry, abuse controls, and audited quota administration; see `saas/PHASE_3_ROADMAP.md`.

## Phase 3.3C.3 — Persistent viewer sessions and concurrent viewer quota (2026-11-26)

**Status: PASS; Phase 3.3C.3 is closed for the requested focused acceptance scope.** Runtime validation used isolated PostgreSQL 16.13, project `phase33c3-viewer`, synthetic tenant/Broadcast data, loopback-only endpoints and an isolated FFmpeg→MediaMTX source. The protected preview, `phase33b1-saas-acceptance`, demo and Surveillance deployments/volumes were not targeted. Existing Camera/Broadcast runtime suites were not repeated.

### Focused Java tests

Filtered Gradle invocations were run separately with `--no-daemon --no-parallel --max-workers=1`. The first exact run selected `ViewerSessionServiceTest`, `PlaybackTokenServiceTest`, `BroadcastTenantOwnershipTest` and `BroadcastPlaybackHttpIsolationTest`: 20 test cases passed, zero failed/errors/skipped. Separate invocations selected `BroadcastQuotaLifecycleTest` (5 passed), `BroadcastControllerTenantAuthorizationTest` (7 passed) and the exact method `BroadcastPlaybackControllerTest.viewerQuotaErrorIncludesStableCodeMetricUsageAndLimit` (1 passed); all had zero failures/errors/skips. Matching JUnit XML reports were inspected after each invocation and contained only the selected classes/method. Total passing cases in these focused runs: 33 (source methods/cases; assertions are not additional tests).

Coverage includes independent per-tab credentials and hash-only persistence, idempotent renewal, expired credential replacement, tenant/Broadcast credential binding, PRIVATE membership/user binding, quota-authority fail-closed behavior, session grant claim validation, private playback revocation, HLS proxy authorization/rewriting, revocation on Broadcast disable/delete/visibility change, membership-scoped no-store viewer usage, and the stable `VIEWER_QUOTA_EXCEEDED` error payload. `viewerCannotEnableOrCreateBroadcastAndCannotAcquireBroadcastLease` also passed within the controller class.

### Runtime and browser results

| Scenario | Result | Evidence |
|---|---|---|
| Schema and restricted roles | **PASS** | Isolated PostgreSQL 16.13 applied Identity V6 and Stream V5. `stream_migrator` owns `public.viewer_sessions`; `stream_runtime` has DML via service default privileges and EXECUTE only on the new fixed-search-path `identity.effective_viewer_quota(uuid,text)` helper. No app role received database/role/DDL privileges or cross-service table DML. |
| Multi-instance last-slot admission | **PASS** | With limit 2 and one existing active session, two simultaneous HTTP requests to Stream instances A and B returned `[200, 429]`; persisted active usage ended at exactly 2. This was one bounded two-request race, not a load test. |
| Renewal and tab identity | **PASS** | Same credential renewed through the second instance with HTTP 200, retained the same session UUID and changed the signed playback grant; independent credentials admitted distinct UUIDs. The database stored one active row for the renewed credential. |
| Expiry and cleanup | **PASS** | A synthetic session manually moved past `expires_at` immediately changed to `0 active / 1 expired`; the scheduled bounded cleanup later deleted that row. |
| Explicit release | **PASS** | Synthetic PUBLIC admission returned 200 without echoing the credential; DELETE release returned 204. |
| Browser HLS renewal | **PASS** | Playwright played real synthetic HLS for 111 seconds across four unique grants; one session UUID was retained, 109 HLS responses succeeded, and the permanent watch URL had no credential/token. Player heartbeat renewed at the configured short test TTL. |
| PUBLIC/PRIVATE, cross-scope and outage checks | **PASS (focused)** | Unit/HTTP-isolation cases verified anonymous PUBLIC admission, current membership for PRIVATE, user/tenant binding, rejection of reuse across tenant/Broadcast, and fail-closed quota authority errors. These were focused service/controller tests, not browser testing of PRIVATE login. |
| Dashboard viewer usage | **PASS (focused)** | Controller tests verify active membership scoping, selected-tenant service call, no-store response and denial before service access without membership. Frontend production build passed. No authenticated Dashboard browser quota-display scenario was run. |

The first browser attempt was **BLOCKED** because the temporary FFmpeg publisher had expired and the synthetic RTSP path was unavailable; after restarting only that isolated publisher, the bounded browser run passed as above. No running preview Broadcast or publisher was changed. An earlier selected `BroadcastPlaybackControllerTest` run also had one unrelated failure in its pre-existing 32-fetch semaphore timing assertion (`all 32 slots must be occupied upstream expected true but was false` after five seconds); it was not rerun because that case is unrelated and drives 32 concurrent fetches. It is recorded as **FAIL (unrelated test attempt)**, not as a passing result or a viewer-session defect.

### Overall result

**PASS:** Implementation, migration application under restricted runtime identities, 33 focused Java cases, bounded two-instance last-slot admission, renewal, expiry/cleanup, explicit release, and real browser HLS renewal. **FAIL:** One unrelated prior semaphore timing case described above. **BLOCKED:** None for the stated C.3 closure gates. **NOT RUN:** full regression, stress/load/soak, authenticated Dashboard browser display, distributed anti-abuse controls, production migration, public ingress and billing. Remaining known risks: per-process rate limiting is not distributed; PUBLIC anonymous session churn has no account/device policy; 90-second inactivity expiry delays slot reclamation after an unclean browser close. Phase 3.3C.3 is closed for the acceptance scope; see `saas/USAGE_QUOTAS.md` and `saas/PHASE_3_ROADMAP.md`.
