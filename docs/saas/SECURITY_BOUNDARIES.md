# Phase 3.2A — Security boundary implementation status

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
