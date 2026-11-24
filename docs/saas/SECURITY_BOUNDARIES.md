# Phase 3.2A — Security boundary implementation status (historical snapshot)

**Streaming database target:** PostgreSQL 16.13 (`postgres:16.13-alpine`) for application and Keycloak databases. Historical PostgreSQL 17 test runs below describe the environment used at that time; they do not define the current target or a required acceptance gate. Identity V1/V2 have passed isolated PostgreSQL 16.13 Flyway and concurrency acceptance.

## Phase 3.2D multi-tenant security acceptance (2026-11-24)

**Phase 3.2D security acceptance: PASS. Public ingress: NOT APPROVED.** Encrypted HLS key retrieval and the hostile `EXT-X-KEY` URI guard now have focused HTTP-boundary evidence using a real HMAC playback grant. Absolute and encoded URI references are rejected before any referenced resource request; redirects are not followed. No existing Surveillance container, volume, database, or production data was targeted.

All test resources used Compose project `phase32d-accept-20261123`, loopback-only ports and dedicated test users/tenant rows. The live service JARs ran against that project's disposable PostgreSQL/Kafka/MediaMTX/Keycloak only. No public ingress, load test, production migration, or broad regression was run. Previously passing interceptor and Browser E2E evidence is reused below and was not repeated.

### Phase 3.2D evidence matrix

| Scenario | Status | Exact evidence / observed result | Previous evidence reused / remaining risk |
|---|---|---|---|
| Camera list is tenant-scoped; absent membership is denied | PASS | `CameraControllerTenantAuthorizationTest.tenantCameraListUsesMembershipScopedRepositoryPath`; `CameraControllerTenantAuthorizationTest.absentMembershipDeniesListAndCameraMutationsBeforeAnyLookup` | Controller method tests verify tenant-qualified service delegation; no separate full Spring HTTP binding test. |
| Camera GET, update, delete, heartbeat and stream-check hide cross-tenant/unassigned rows | PASS | `CameraControllerTenantAuthorizationTest.crossTenantCameraGetIsHiddenWithoutUnscopedFallback`; `CameraControllerTenantAuthorizationTest.crossTenantCameraMutationsUseScopedLookupsAndReturnNotFound`; `CameraControllerTenantAuthorizationTest.unassignedLegacyCameraIsHiddenByTenantScopedReadAndMutationRoutes` | Return 404 and avoid unscoped service calls; no credentials are returned by those failure paths. |
| Stream start, stop, get/status, list, HLS URL and remove | PASS | `StreamTenantAuthorizationTest.missingMembershipDeniesEveryStreamRouteBeforeResourceLookup`; `StreamTenantAuthorizationTest.crossTenantStartReadUrlAndRemoveAreHiddenBeforeStreamManagerAccess`; `StreamTenantAuthorizationTest.streamListsUseTenantScopedLeaseViews`; `StreamTenantAuthorizationTest.unassignedLegacyCameraCannotStartReadStatusOrReleaseAStreamLease`; `StreamTenantAuthorizationTest.authorizedStartAndStopUseTenantSpecificConsumerLeaseOperations` | `/url` remains deliberately unavailable in OIDC mode; tenant lease acquisition/release is bound to validated tenant. No separate viewer-lease REST endpoint exists. |
| Broadcast get/list/delete/playback-info and missing membership | PASS | `BroadcastControllerTenantAuthorizationTest.missingMembershipDeniesBroadcastReadDeleteAndPlaybackInfoBeforeServiceCalls`; `BroadcastControllerTenantAuthorizationTest.crossTenantBroadcastReadsDeleteAndPlaybackInfoUseScopedServiceMethods`; `BroadcastControllerTenantAuthorizationTest.unassignedLegacyBroadcastIsHiddenFromTenantListReadDeleteAndPlaybackInfo` | Scoped service calls hide IDs; direct controller method tests, not a full HTTP controller slice. |
| Viewer lease lifecycle | PASS | `StreamTenantAuthorizationTest.authorizedStartAndStopUseTenantSpecificConsumerLeaseOperations`; historical `StreamManagerLeaseTest.releasingBroadcastLeaseDoesNotStopStreamWhileLegacyConsumerRemains`; `StreamManagerLeaseTest.lastConsumerReleaseRequestsExactlyOneStop`; `BroadcastServiceTest.enablingBroadcastAcquiresIndependentLeaseForExistingStream` | No independent viewer lease API is present; Broadcast and Stream consumers use shared StreamManager leases. Historical lease methods were not rerun. |
| OIDC and legacy WebSocket HTTP upgrade chains | PASS | `WebSocketHttpSecurityTest.oidcPermitsUpgradeButDeniesUnrelatedHttpRoutes`; `LegacyWebSocketHttpSecurityTest.legacyPermitsTrustedUpgradeButDeniesUnrelatedRoutes` | Two exact filtered methods passed. Legacy destination fanout additionally reuses `WebSocketEventTenantRoutingTest.legacySurveillanceKeepsExistingGlobalDestinations`; no Surveillance runtime was started. |
| Live Gateway STOMP anonymous CONNECT, tenant CONNECT and destination isolation | PASS | Gateway `127.0.0.1:18082/ws`, origin `http://localhost:15173`: anonymous CONNECT was closed without CONNECTED; tenant A and B received CONNECTED; cross-tenant and `/topic/streams` subscriptions returned STOMP ERROR. | First probes used the wrong test origin and got 403; after aligning the isolated WebSocket service to the configured player origin, the complete check passed. |
| Live Kafka persisted-owner routing, forged tenant claim and metadata redaction | PASS | Kafka `stream.websocket.events` event for Camera A carried a forged Tenant B claim; through Gateway, only A received it; internal HLS URL and diagnostic error were absent from delivered frame. | `WebSocketEventTenantRoutingTest` unit tests are reused, not rerun. Kafka event tenant claim is ignored in favor of persisted Camera ownership. |
| Live active STOMP subscription after membership revocation | PASS | Revoked Tenant A membership in isolated DB, published a subsequent Camera A status event; active A connection received no event; membership restored to ACTIVE afterward. | No Surveillance destination was modified. |
| Live MediaMTX-backed PUBLIC/PRIVATE HLS, playlists and segments | PASS | Through Gateway: anonymous PUBLIC grant 200; authenticated A PUBLIC grant 200; anonymous PRIVATE 401; A PRIVATE 200; B requesting A PRIVATE grant 401. PUBLIC and PRIVATE playlists returned 200 `#EXTM3U`; segments returned 200 (about 1.9/2.2 KiB). | Prior Chromium page/iframe decode result from `docs/broadcast-integration-tests.md` is historical and reused; no browser E2E was repeated in this phase. |
| Live HLS grant expiry, tamper, membership revocation, disable/delete | PASS | 15-second grant returned 401 after 17.0 seconds; modified grant previously returned 401; existing private grant and renewal returned 401 after membership revocation; fresh grant returned 401 after disable and renewal 410; delete returned 204, playback-info 404 and old grant 404. | Playback tokens remain media-only and are not reused as management Bearer tokens. |
| Gateway direct `/media-hls/**` bypass | PASS | Anonymous direct request returned 401; authenticated direct request returned 403. | MediaMTX is reached only through the Broadcast authorization proxy for public playback. |
| Live encrypted HLS playlist and key retrieval | PASS | Isolated synthetic H.264 HLS playlist used `#EXT-X-KEY:METHOD=AES-128,URI="aes.key"`; authenticated token grant `GET /api/playback/{publicId}/token` returned 200; through Gateway, `GET /broadcast-play/{publicId}/index.m3u8?token=…` returned 200 and rewrote the key reference to a protected `/broadcast-play/{publicId}/aes.key?token=…` URL; authorized key GET returned 200, exactly 16 bytes, SHA-256 `8df375a7b8d7d2dab7b92a5232d54e5aeb6ac2c5673428e98178d26cb116d8cb`. Playlist contained neither the raw URI `aes.key` nor the upstream host. | Browser AES decryption was NOT TESTED. This closes the missing live key-retrieval evidence only. |
| Encrypted key authorization and revocation | PASS | Missing/empty grant: 401; modified/forged grant: 401; 15-second grant after expiry: key GET 401; Tenant B private grant request: 401; key GET after membership revocation: 401; after Broadcast disable: 401; after delete: 404. Disable API returned 200; delete API returned 204. Malformed encoded resource path returned 400. | Synthetic membership was restored after the revocation check. Only isolated synthetic rows were changed. |
| Hostile external key URI rejection / SSRF guard | PASS (focused HTTP boundary) | `BroadcastPlaybackHttpIsolationTest.validHmacGrantReachesValidatorAndRejectsAbsoluteEncryptionKeyUri`: real `PlaybackTokenService` issues the unexpired HMAC grant and real `BroadcastService.authorizePlayback` accepts it; the MockMvc HTTP request fetches exactly the expected source playlist, then returns 502 for `URI="https://outside.invalid/key"`. `MockRestServiceServer.verify()` confirms no request was made to the referenced URI. | No full Gateway/live-stream-service rerun was needed: this targets the controller/proxy boundary with a valid grant and a strict upstream request expectation. Previous live 401 was emitted before this validator; see root-cause note below. |
| Redirect, encoded URI and traversal protections | PASS (focused tests) | `BroadcastPlaybackHttpIsolationTest.playbackProxyRejectsUpstreamRedirectWithoutContactingRedirectTarget`: 302 maps to 502; the loopback redirect target received 0 requests and no Authorization header. `BroadcastPlaybackHttpIsolationTest.encodedTraversalAndUnrecognizedUriAttributesFailClosed`: encoded/double-encoded slash and dot sequences, query/fragment suffixes, lower-case external `uri`, and unquoted URI attributes all throw 502 before rewriting. Resource names are restricted to the proxy's safe ASCII filename set. | Playback RestClient now uses JDK HTTP with redirects disabled. Redirect testing used two local loopback HTTP servers; no external host was contacted. |
| Secret leakage in checked logs | PASS (bounded scan) | Persisted Stream, Gateway and synthetic-upstream logs contained no match for the access/playback tokens, key bytes, key Base64 or key SHA-256. | Logs do not record access payloads by design. Scan scope was the captured test log files, not an independent log aggregator. |
| Browser AES segment decryption | NOT TESTED | No browser player was used in this encrypted-key check. | Existing Browser E2E results were not repeated. |
| Fresh service Flyway histories and additive legacy upgrade | PASS | Identity V1; Camera baseline 0 + V1–V3; Stream baseline 0 + V1–V3 all recorded `success=true` in isolated PostgreSQL 17. A pre-owner backup restored Camera/Broadcast/lease rows `1:1:1`; after upgrade Camera/Broadcast remained `tenant_id IS NULL`, scoped queries returned `0:0`, and all five tenant indexes existed. | Stream startup intentionally clears durable-less `LEGACY` leases using `StreamManager.clearOrphanedLegacyLeases`; the pre-upgrade restore proves the synthetic row existed before restart. This cleanup is not a tenant backfill. |
| Pre-ownership and post-ownership backup/restore | PASS | Isolated `pg_dump -Fc`/`pg_restore`: pre-owner Camera/Broadcast/lease rows restored; post-owner Camera/Broadcast/lease retained their IDs and synthetic tenant UUID. | No destructive rollback was attempted after ownership assignment. No down migration exists; recovery is backup/restore or forward repair. |
| Surveillance data and containers | PASS (scope evidence) | Every database and service operation in this phase targeted the named `phase32d-accept-20261123` test project or loopback JVMs. No command targeted the Surveillance Compose project or its volumes/databases. | This is an operation-scope statement, not a production-data integrity scan. |

The focused Java executions used one Gradle worker and `--no-parallel`; follow-up methods used exact `--tests` filters. The initial Camera unassigned-row test attempt failed because its mock returned `null`; the fixture was corrected and that exact method passed on rerun. The previous hostile-URI probe returned 401 before any upstream request. In `BroadcastPlaybackController.file`, `authorizePlayback` runs before the upstream fetch and playlist rewrite; its privacy-preserving 401 covers an invalid/expired signature, broadcast/version state, or revoked membership. The previous artifacts did not preserve the grant expiry, playback version or membership snapshot, so the specific subcondition cannot be reconstructed. The deterministic valid-grant test now proves the validator path itself returns 502 and makes no URI-derived request. Phase 3.2D evidence acceptance is complete; Streaming remains private pending a separate public-route review and deployment controls.

## Phase 3.2C cross-transport evidence (2026-11-23)

**Overall: BLOCKED for Phase 3.2 completion and public ingress.** Focused unit/controller tests, SQL application on a disposable PostgreSQL 17 tmpfs container, and static Compose validation passed. There was no live Kafka broker, STOMP client, service-backed Flyway startup, or MediaMTX HLS request in this validation. Camera route permutations, complete stream/broadcast IDOR matrix, and migration rollback are not fully verified.

### Current boundaries

In Streaming OIDC mode, STOMP `CONNECT` verifies the bearer JWT and requires `X-Tenant-ID` to resolve to active membership plus `camera.read`. The selected tenant is bound to the socket principal. `SUBSCRIBE` permits only `/topic/tenants/{validatedTenant}/cameras/status` and `/topic/tenants/{validatedTenant}/streams`; arbitrary destinations, tenant mismatches and client `MESSAGE` frames are rejected. Outbound frames re-check membership, so revoked sessions stop receiving queued and future messages. Gateway `/ws` is a deliberate handshake exception because browser WebSocket clients cannot provide the normal Authorization header; authentication happens at STOMP CONNECT. WebSocket service remains private and origin patterns must be explicitly configured.

WebSocket Kafka listeners resolve Camera tenant ownership from the persisted Camera row. They do not route based on event tenant claims, drop unassigned/unknown resources, and redact error/source-media fields. Stream-service Kafka listeners similarly resolve Camera ownership through a narrow signed workload token with `camera.ownership.read`, no tenant claim, and a persisted active/deleted ownership lookup. Camera deletion uses an after-commit tombstone so cleanup is scoped to the actual deleted owner's tenant. Forged event tenant values do not select the cleanup tenant. Unknown ownership is ignored; internal lookup failures propagate for Kafka retry.

The stream status persistence path resolves tenant from the stored Camera and updates only `(camera_id, tenant_id)` rows. Streaming Camera, Broadcast and lease ownership columns remain nullable; unassigned legacy rows are not claimable by Streaming and tenant-qualified queries do not return them. Surveillance retains its legacy behavior and global WebSocket destinations when `APP_SECURITY_MODE=legacy`.

Protected HLS behavior has MockMvc evidence: PRIVATE anonymous token issuance and revoked authorization are denied; playlist references to segments and encryption keys are rewritten through the token-protected proxy; cache/referrer headers are constrained. The upstream MediaMTX itself was mocked, so actual network HLS, cross-tenant segment access and revocation while a browser is playing remain unverified. Direct `/media-hls/**` and `/hls/**` remain denied through Streaming public gateway security; do not add public ingress.

### Security evidence matrix

`PASS` means the exact focused test or isolated SQL query named below passed; it does not imply live service-to-service E2E. `BLOCKED` means that route/transport behavior still lacks decisive evidence.

| Transport / operation | Evidence and exact reference | Status |
|---|---|---|
| Camera REST create ownership | `CameraControllerTenantAuthorizationTest.createAssignsTenantFromMembershipContext`; `CameraTenantOwnershipTest.streamingCreateTakesOwnerOnlyFromVerifiedServiceArgument` | PASS |
| Camera REST read cross-tenant hiding | `CameraControllerTenantAuthorizationTest.missingMembershipDoesNotFallBackToUnscopedCameraLookup`; `CameraTenantOwnershipTest.tenantLookupHidesUnknownAndOtherTenantCameras` | PASS |
| Camera REST list/update/delete/heartbeat/check-stream cross-tenant matrix | Focused tests do not cover every controller operation/HTTP route | BLOCKED |
| Stream REST stop role and cross-tenant denial | `StreamTenantAuthorizationTest.viewerCannotStopCameraStream`; `StreamTenantAuthorizationTest.tenantMemberCannotStopAnotherTenantsCamera` | PASS |
| Stream REST start/get/list/HLS-URL cross-tenant matrix | No controller-level denial test for every operation; OIDC HLS response shape is not live-verified | BLOCKED |
| Broadcast create ownership and forged Camera tenant | `BroadcastTenantOwnershipTest.creatingBroadcastChecksCameraThroughTenantDelegationAndPersistsOwner`; `CameraEventsConsumerTenantTest.streamingScopesCameraDeletionCleanupByTenant` | PASS |
| Broadcast management role/object scope | `BroadcastControllerTenantAuthorizationTest.viewerCannotEnableOrCreateBroadcastAndCannotAcquireBroadcastLease`; `BroadcastControllerTenantAuthorizationTest.managementMutationUsesSelectedValidatedTenantForObjectLookup`; `BroadcastTenantOwnershipTest.crossTenantBroadcastLookupUsesTenantQualifiedQueryAndReturnsNotFound` | PASS |
| Broadcast delete/list/playback-info complete controller IDOR matrix | No denial assertion for every management route | BLOCKED |
| Viewer lease stop/release tenant ownership | `StreamTenantAuthorizationTest.tenantMemberCannotStopAnotherTenantsCamera`; tenant-qualified Broadcast/lease service and Kafka cleanup test above | PASS (focused) |
| Private/public playback policy and revoked membership | `BroadcastTenantOwnershipTest.privatePlaybackIsRevokedWhenMembershipIsRevoked`; `BroadcastTenantOwnershipTest.privatePlaybackGrantFromAnotherTenantIsRejected`; `BroadcastTenantOwnershipTest.unassignedPublicBroadcastIsNotPlayableInStreaming` | PASS (service-level) |
| Protected HTTP HLS proxy and key/segment rewriting | `BroadcastPlaybackHttpIsolationTest.privateGrantRevokedBeforeHttpProxyDoesNotReachMediaMtx`; `BroadcastPlaybackHttpIsolationTest.authorizedHttpPlaylistProxyRewritesEveryMediaReference`; `BroadcastPlaybackHttpIsolationTest.privateTokenHttpEndpointDoesNotIssueAnonymousGrant` | PASS (mock upstream) |
| Real MediaMTX HLS cross-tenant/revocation boundary | No live HTTP playback test in this run | BLOCKED |
| WebSocket CONNECT authentication, tenant selection, SUBSCRIBE destination, destination allowlist | `TenantStompSecurityInterceptorTest.authenticatedMultiTenantUserMayConnectPerTenantButCannotSubscribeAcrossTenant`; `TenantStompSecurityInterceptorTest.connectRequiresJwtAndValidatedMembershipForSelectedTenant` | PASS (interceptor unit) |
| WebSocket membership revocation on active session | `TenantStompSecurityInterceptorTest.activeSubscriptionStopsReceivingFramesAfterMembershipRevocation` | PASS (outbound interceptor unit) |
| Kafka status event tenant routing and metadata redaction | `WebSocketEventTenantRoutingTest.cameraStatusRoutesUsingPersistedOwnerAndRedactsReason`; `WebSocketEventTenantRoutingTest.streamStatusRoutesFromPersistedOwnerAndDoesNotForwardInternalMediaDetails`; `WebSocketEventTenantRoutingTest.unassignedAndUnknownKafkaResourcesAreNotBroadcast` | PASS (listener unit) |
| Kafka Camera deletion tenant spoofing and tombstone scoping | `CameraEventsConsumerTenantTest.streamingScopesCameraDeletionCleanupByTenant`; `CameraEventsConsumerTenantTest.streamingIgnoresUnscopedCameraDeletionEvents` | PASS (consumer unit) |
| Status update ownership derived from persisted Camera | `CameraStatusTenantEventTest.streamingStatusMutationUsesPersistedCameraOwner`; `CameraStatusTenantEventTest.streamingDoesNotMutateOrPublishStatusForUnassignedCamera` | PASS (service unit) |
| Legacy Surveillance WebSocket/status compatibility | `WebSocketEventTenantRoutingTest.legacySurveillanceKeepsExistingGlobalDestinations`; `CameraTenantOwnershipTest.legacyLookupRemainsUnscopedForSurveillance` | PASS (unit) |
| Isolated ownership migrations and unassigned-record backfill check | SQL V1–V3 files applied to disposable PostgreSQL 17; counts: camera unassigned=1, broadcast unassigned=1, lease unassigned=1, tenant-scoped camera visible=0; six expected tenant indexes present | PASS (SQL application only; not Flyway lifecycle) |
| SQL rollback before ownership assignment | Reverse DDL executed on the same disposable DB while all ownership values were NULL; tenant columns and identity schema were absent after rollback | PASS (manual isolated rollback only) |
| Flyway startup/history and rollback after ownership assignment | Service Flyway startup/history was not exercised; no down migrations exist; dropping assigned tenant ownership is data loss | BLOCKED |
| Compose boundary | `docker compose -f docker-compose.demo.yml config -q`; `docker compose --env-file /tmp/phase32b-compose.env -f docker-compose.streaming.yml config -q` | PASS (static only) |

Focused executions: WebSocket service **7/7 PASS** after fixing the accessor-less STOMP message handling; stream-service selection initially had **12 PASS / 1 failed test assertion**, and only the failed method was corrected/re-run to PASS; Gateway **1/1 PASS**. The first failed WebSocket run was diagnostic and is not counted as final passing evidence. No full regression, public ingress, real Kafka/STOMP, live HLS, load, or existing Surveillance container operation was performed.

The temporary PostgreSQL container was named `phase32c-isolated-pg`, used `postgres:17-alpine`, tmpfs storage, and loopback-only port 25439. SQL migration sources were applied directly with `psql`; this proves PostgreSQL accepts the SQL and nullable ownership leaves synthetic historical rows unassigned. Then manual reverse DDL was executed against the disposable database while tenant ownership columns contained only NULLs; the tenant columns and identity schema were absent afterward. This does not prove Flyway startup/history or rolling upgrades. It does not authorize dropping ownership after assignment. No backup/restore was performed; rollback after tenant data exists requires a reviewed backup/restore plan or forward repair migration, not destructive column removal.

### Remaining blockers

1. Add controller-level negative tests for every Camera CRUD/status route, Stream start/read/list, Broadcast read/list/delete/playback-info, and explicit viewer lease acquisition/release.
2. Run STOMP CONNECT/SUBSCRIBE and revocation through a live isolated WebSocket service and Kafka broker, with two tenants.
3. Run cross-tenant, expired-token, disabled/deleted and revoked-membership playback against live isolated MediaMTX through the Gateway HTTP boundary.
4. Exercise Flyway startup/history on isolated databases and test restore/rollback for pre-ownership and post-ownership data; only manual pre-assignment reverse DDL has been verified.
5. Keep Streaming private until all blockers pass. Recording, Analytics, search and any other consumer that can access tenant data remain excluded/private until their tenant context is verified.

## Phase 3.2B resource authorization status (2026-11-23)

In `APP_SECURITY_MODE=oidc`, Camera `/api/**` requires a JWT validated for issuer, JWKS signature, audience and standard lifetime claims, plus `X-Tenant-ID` as a selector. The selector is accepted only after `TenantAuthorization` confirms an active membership and permission in identity tables. Camera list/get/update/delete/heartbeat/check-stream use tenant-scoped service methods; cross-tenant and unassigned records return not-found. Create assigns owner exclusively from the resolved context. OIDC Camera responses use a safe DTO that omits RTSP addresses and credentials.

Stream `/api/streams/**` requires `camera.read` or `stream.manage` and verifies Camera ownership before stream operations. Tenant lease acquisition/release is scoped. OIDC responses omit HLS URLs and diagnostic error strings; direct stream URL compatibility is unavailable in OIDC. A session remains keyed by Camera ID and shared by Broadcast/Surveillance consumers; only the last consumer release can stop the worker.

Broadcast management `/api/broadcasts/**` requires `broadcast.read` or `broadcast.manage`. Creation verifies Camera through Camera's workload-protected internal lookup, and assigns Broadcast tenant from that context. Scoped Broadcast queries/mutations hide cross-tenant IDs. PUBLIC playback remains anonymous only when Broadcast is explicitly PUBLIC and enabled. PRIVATE token issuance requires an active tenant member with `playback.view`; each media request rechecks current membership, so revocation denies further playlist/segment/key access. The short-lived token remains separate from management Bearer/OIDC credentials.

The Stream-to-Camera internal API `/internal/cameras/**` accepts only a short-lived signed workload token (HS256, issuer `https://stream-service.internal`, audience `camera-service`, expiry, UUID tenant claim). Tenant comes from verified JWT claims, never a header. A caller cannot request an unowned Camera. Shared secret is required by Streaming configuration and must be rotated/managed outside source control. Rust ingest-agent receives its existing dedicated ingest token, not browser OIDC credentials.

Camera deletion Kafka payload now carries `tenantId`; Streaming ignores unscoped legacy delete events, and scoped cleanup removes only the tenant's Broadcast/leases. Stream status event records themselves do not yet carry or validate tenant context. Existing global WebSocket status fanout is therefore still a SaaS isolation gap; keep WebSocket and internal service ingress private.

| Surface | Streaming OIDC policy | Remaining boundary |
|---|---|---|
| Camera `/api/**` | User JWT + active membership/role + tenant-qualified Camera access | Add controller-level tests for every mutation/list route; LBS/category ownership is outside this phase. |
| Camera `/internal/cameras/**` | Signed workload JWT + signed tenant-scoped lookup | Shared symmetric key rotation and separate workload identities remain future work. |
| Stream `/api/streams/**` | User JWT + membership/role + Camera ownership | Stream status Kafka events and WebSocket fanout are not tenant-filtered. |
| Broadcast `/api/broadcasts/**` | User JWT + membership/role + tenant-qualified Broadcast | Tenant provisioning and auditing are absent. |
| Public playback | Anonymous only for enabled PUBLIC; PRIVATE requires active membership; signed grant checked per media request | Add full endpoint tests for anonymous PRIVATE, token expiry/tamper/disable, membership revocation at HTTP boundary. |
| `/media-hls/**`, `/hls/**`, `/ws/**` | Continue denied/private in Streaming gateway/resource security | Do not widen host binds/public ingress. |
| Recording/Analytics | Not tenant-scoped by this phase | Keep private/unavailable for SaaS. |

Current application permission matrix (roles come from active application membership, never browser claims):

| Role | Permissions |
|---|---|
| OWNER | `tenant.manage`, `identity.read`, `camera.read/manage`, `stream.manage`, `broadcast.read/manage`, `playback.view`, `recording.read`, `analytics.read` |
| ADMIN | `identity.read`, `camera.read/manage`, `stream.manage`, `broadcast.read/manage`, `playback.view`, `recording.read`, `analytics.read` |
| OPERATOR | `camera.read/manage`, `stream.manage`, `broadcast.read/manage`, `playback.view` |
| VIEWER | `camera.read`, `broadcast.read`, `playback.view`, `recording.read`, `analytics.read` |

Security validation is focused unit/controller coverage only. Compose validation is static; no service containers or databases were started, no migrations were run, and no public endpoint was exposed. This is not a public-ingress approval.

In Streaming OIDC mode, the gateway validates a Bearer JWT against configured JWKS, issuer and `streaming-api` audience; default Spring validators also reject expired and not-yet-valid tokens. All routes require authentication except health/info and the existing Broadcast playback/token routes, which remain subject to Broadcast visibility and enabled-state checks in stream-service. `/media-hls/**`, `/hls/**` and `/ws/**` are denied at the Streaming gateway. Stream-service separately requires OIDC for management APIs and keeps playback policy routes available for anonymous PUBLIC playback. Identity-service requires the same JWT validation except health. The Streaming gateway, player and Keycloak development HTTP port bind to loopback; the other Streaming service ports are not host-published.

Surveillance remains on its legacy gateway security chain and is not configured with Keycloak or identity-service. This preserves current behavior, but also means the demo is trusted-network-only, not suitable for public ingress.

The identity API is exposed through the authenticated gateway at `/api/v1/identity/**`. Discovery maps the validated issuer+subject to an internal user. Membership results come from application tables; no group/role claim from Keycloak grants tenant membership. No membership provisioning endpoint exists. Role evaluation is not yet wired into Camera/Broadcast/Stream APIs because these domain records have no tenant owner. Camera-service and websocket-service do not yet validate service/user credentials when called directly on the private Compose network. Therefore OIDC protects the public Streaming gateway boundary only; this is not a complete internal zero-trust or tenant isolation implementation.

No public deployment, browser login, live token validation against Keycloak, authenticated HLS, or private Broadcast playback test was performed in this change. Compose validation is static only. Do not widen host bindings or ingress based on these changes.

---

# Phase 3.1 — Security boundaries and exposure inventory

## Deployment boundary

Deployment docs characterize Streaming as a private SaaS foundation, not approved for public ingress. Example Streaming Compose exposes gateway/player on loopback while MediaMTX, agent, Kafka, PostgreSQL and application services remain internal. Surveillance demo retains legacy host ports and publishers; docker/mediamtx.yml has no authentication. Network privacy is therefore a compensating control, not user authentication. Do not add public DNS or widen ingress until route inventory is fixed.

## Phase 3.1 endpoint snapshot and remaining policy

The following table records the pre-3.2A endpoint audit. Apply the Phase 3.2A status above for changes made in this phase; its target column still describes outstanding authorization work.

| Endpoint/surface | Observed current behavior | Target |
|---|---|---|
| Gateway /api/v1/cameras/** | Routes CRUD, heartbeat, stream-check to camera-service; no Spring Security/controller authentication | OIDC plus membership/resource checks in gateway and Camera service |
| Camera /internal/cameras/{id}/connection | Internal connection lookup can return RTSP connection data; controller has no service auth | Private network plus workload identity and tenant-camera authorization; never public |
| Camera /test | Test controller route | Disable/remove from deployed profile or keep private |
| Gateway /api/v1/streams/** | Stream start/stop/list/get/HLS URL routes lack user auth | Tenant-scoped operator permissions in stream-service |
| Gateway /api/v1/broadcasts/** | Broadcast management checks one global static Bearer secret | User OIDC, tenant membership, role and audit |
| /api/playback/{publicId}/token | No login; issues only for enabled PUBLIC Broadcast | Anonymous only with explicit owner public-sharing setting; private requires member/view grant |
| Gateway /api/v1/recordings/** and /recordings/** | Gateway routes to recording-service without gateway authorization; service routes are not in the default Streaming assembly but can exist in demo | Keep private; if enabled for SaaS, require tenant-scoped ownership checks and protected media/object access |
| Gateway /api/v1/analytics/** | Routed to analytics-service without gateway authorization; may be absent in Streaming assembly | Keep private; if enabled for SaaS, require tenant-scoped job/event access and authorized background context |
| Gateway /hls/** | Routes to stream-service HLS controller without gateway authorization | Keep private or remove from public route table; public Broadcast media must use the protected playback proxy |
| /broadcast-play/** | Short-lived HMAC token protects proxy playlist/segments and checks visibility/enabled/generation | Bind grants to tenant, broadcast, media audience, expiry and revocation version; reauthorize renewal/fetch |
| /media-hls/** | Gateway direct MediaMTX compatibility proxy without user/tenant auth. MediaMTX streaming role auth is separate; demo has no auth | Do not expose publicly; remove from public routes; all player media via protected proxy |
| /ws/** and service /ws | STOMP endpoints allow wildcard origins; global camera/stream topics; no CONNECT or SUBSCRIBE auth | Authenticate handshake/CONNECT; authorize each topic by tenant; exact origins; tenant-filtered destination |
| Actuator | Selected health/info/metrics; some configs show detailed health | Internal-only; least detail externally; authenticate metrics or isolate network |
| Rust agent /health,/ready,/metrics | Explicitly unauthenticated. Pipeline routes authenticate only when AGENT_API_TOKEN is configured; unset token disables auth. HLS static file route is registered. Compose Streaming supplies token but code default is optional | Fail startup in Streaming without token; private-only health/metrics and no gateway route |
| MediaMTX | Streaming internal auth with separate publish/read users, API disabled; demo config unauthenticated | Private listeners and tenant/path scoped credentials; no browser credentials |
| Kafka | Internal broker; CameraRegisteredEvent currently contains rtspUrl | Authenticated broker/ACLs; tenant/event schema; remove secrets from events and audit retention |

CameraEntity JSON ignores credential columns, but does not restrict API access. Camera connection responses and Kafka payloads must be considered sensitive. CORS is not authorization. Gateway route filtering alone is not an adequate service boundary.

## PUBLIC and PRIVATE policy

PUBLIC means owner consent for anonymous viewing, not a tenant-wide default. New broadcasts remain PRIVATE. A permanent watch/iframe URL contains no bearer secret. PRIVATE URL shell may be shareable, but playback demands login and active tenant membership or a separately defined invitation.

Every token is media-only, short-lived, tenant/broadcast/audience-bound and revocable by generation. Renewal re-evaluates policy. Disable/delete/privacy change, membership revocation or invite revocation blocks issuance. Playlist, segment and encryption key requests use the same check. Keep no-referrer, no-store and redact token query parameters in application, gateway, ingress, WAF and analytics logs. A random public ID is not authorization.

## Highest-priority risks before public ingress

1. Camera and Stream management APIs are open.
2. WebSocket permits wildcard Origin and global topic subscription, potentially leaking tenant status.
3. /media-hls bypasses Broadcast visibility and token rules.
4. Agent auth is optional in code.
5. CameraRegisteredEvent carries RTSP URL; credentials embedded in URL can persist in Kafka/history/logs. Replace with credential-free metadata and authenticated lookup; audit retention and rotate exposed credentials.
6. MediaMTX users have shared UUID-wide permissions, not tenant isolation.
7. UUID-based repository lookups/lists have no tenant predicates; UUID entropy is not authorization.
8. Broadcast rate limiting is process-local and remote-address keyed; forwarded address trust needs explicit proxy configuration.
9. Test/debug/health/metrics surfaces must remain private.
10. Never log raw credentials, authorization headers, full RTSP URLs or tokenized playlist URLs.

## Public ingress allowlist

When approved later, expose TLS gateway and static player only. Explicitly route public watch, token and protected media endpoints plus authenticated management APIs. Do not publish PostgreSQL, Kafka, Camera, Stream, Recording, Analytics, Rust agent, MediaMTX RTSP/HLS/API/metrics, or direct websocket-service ports. Internal services need workload identity as well as private networking. Surveillance keeps its existing trusted-installation network behavior and is not made public by enabling Streaming.

## Phase 3.3A — Organization and invitation management

Streaming-only management endpoints are routed by the Gateway to identity-service: `/api/v1/organizations/**` and `/api/v1/invitations/accept`. They use the existing OIDC Resource Server boundary. The legacy Gateway security chain explicitly denies these new paths while continuing to permit the existing trusted Surveillance routes; Surveillance Compose does not include identity-service. Do not add these routes to public unauthenticated ingress.

Tenant access is checked against active application-owned membership on each request. OWNER alone can rename organizations and manage any membership/role; ADMIN can invite, revoke invitations for, and remove/change roles of OPERATOR/VIEWER only. All active members can read organization/member details. Last active OWNER departure, removal and demotion are rejected in service logic and protected by deferred database constraints. Membership removal is a status transition to REVOKED; current resource services re-resolve membership rather than trusting IdP groups.

Invitation API returns a random token only once and stores only SHA-256 bytes. Acceptance derives issuer+subject and email from the validated JWT, requires `email_verified=true`, and locks tenant/invitation before membership mutation. Pending invitations expire after seven days; expiry is materialized when listed or accepted. Development invitation links are disabled by default and, when enabled, must use a loopback HTTP origin. No delivery provider exists. Do not log request bodies containing accept tokens or enable body/access logging for this endpoint.

**Validation limits:** the controller security slice and the PostgreSQL 16.13 V2 migration/constraint-trigger and multi-transaction acceptance have passed. Public ingress remains not approved. Separate production readiness, logging, rate-limit and deployment security reviews remain.

### Phase 3.3A.1 persistence acceptance status (2026-11-24)

**PASS for the declared PostgreSQL 16.13 target.** Identity Flyway V1/V2 were applied by real Identity Service startup to `identity_test_db` only. The preserved V1 fixture, V2 catalogs, unique/partial indexes and both initially deferred constraint triggers were inspected. The 11-method `IdentityPersistenceAcceptanceTest` passed, including simultaneous owner removal/demotion and concurrent acceptance. Final audit found no ownerless tenant, failed migration or leftover helper function. Test identities/invitations remain in the isolated test DB. No production/Surveillance database or volume was contacted.
