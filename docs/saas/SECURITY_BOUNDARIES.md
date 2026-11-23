# Phase 3.2A — Security boundary implementation status (historical snapshot)

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
