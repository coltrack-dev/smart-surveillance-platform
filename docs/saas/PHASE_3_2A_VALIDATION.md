# Phase 3.2A.1 — Live OIDC integration validation

Date: 2026-11-23
Overall: **PARTIAL PASS** — live OIDC login and identity/security checks passed in an isolated environment. This is not a complete streaming or RTSP-to-browser validation.

## Isolation and execution scope

- Compose project: `phase321-oidc`; only its `streaming-postgres`, `keycloak-postgres` and `keycloak` containers were started. The two PostgreSQL services use project-scoped `streaming-db-data` and `streaming-keycloak-db-data` volumes. Their temporary published ports were loopback-only (15433 and 15434); Keycloak was loopback-only on 8180.
- Existing `broadcast-it` containers used ports 5173 and 18081. The test used separate ports: Vite player 5174, Gateway 18082, identity-service 18099, stream-service 18094 and synthetic HLS origin 19088. JVM services, Vite and the HLS stub bound to loopback.
- PostgreSQL images were reused. Keycloak 26.8.2 and Playwright Chromium were downloaded. No Docker application image was built. Identity, Gateway and Stream JARs were built with one Gradle worker using `bootJar` tasks only; no Gradle tests ran.
- The application processes used the isolated Streaming database on 15433. Keycloak used its separate database on 15434. All credentials were generated for this local run, kept in `/tmp/phase32a1-oidc.env` (mode 0600), and are not recorded here.
- The browser smoke ran Chromium headless against `http://localhost:5174`; Keycloak issuer was `http://localhost:8180/realms/streaming`. No public ingress was configured.
- The synthetic HLS origin returned a test playlist and dummy segment. No RTSP camera, camera-service, ingest-agent, MediaMTX, real video decode, or audio was involved.

## Configuration and startup

PASS — resolved Compose configuration:

```bash
docker compose -p phase321-oidc \
  --env-file /tmp/phase32a1-oidc.env \
  -f docker-compose.streaming.yml \
  -f /tmp/phase32a1-oidc.override.yml config -q
```

The override only publishes the two isolated database ports on loopback. Separate Compose project names ensure distinct container, network and named-volume namespaces. The existing Surveillance deployment was not operated on; existing `broadcast-it` containers were not restarted or modified.

Keycloak 26.8.2 started and imported realm `streaming`. OIDC discovery returned the expected issuer, browser authorization endpoint and JWKS URI:

```text
issuer: http://localhost:8180/realms/streaming
authorization endpoint: http://localhost:8180/realms/streaming/protocol/openid-connect/auth
JWKS: http://localhost:8180/realms/streaming/protocol/openid-connect/certs
```

Identity-service Flyway created schema `identity` and successfully applied version 1, `identity foundation`, in the isolated Streaming DB (`flyway_identity_schema_history`: success=true). Stream-service found its three existing migrations current in the same isolated DB. Keycloak data remained in its independent database and volume.

## Browser PKCE and token validation

PASS — a real Chromium login completed through the Vue client and Keycloak authorization-code callback. The observed authorization request used:

- client `streaming-vue` (public client; no browser secret),
- `response_type=code`,
- `code_challenge_method=S256` with a non-empty challenge,
- redirect URI `http://localhost:5174/`.

The returned access token had issuer `http://localhost:8180/realms/streaming`, audience `streaming-api`, a subject, a valid future expiration, a valid not-before time, and a 300-second lifetime. Its signature was accepted by both the Gateway and identity-service, which fetched JWKS from Keycloak. A modified signature was rejected by both services with 401.

### Realm issue found and fixed

The first live login exposed a defect that static configuration and the existing unit tests did not catch. Realm import warned that `web-origins`, `profile` and `email` scopes did not exist. Keycloak 26.8.2's realm then had only the custom audience scope. The access token had valid issuer, audience and signature but no `sub`; the identity `/me` endpoints returned 500 because user identity requires issuer plus subject.

The development realm now defines and attaches an explicit `streaming-basic` scope with Keycloak's `oidc-sub-mapper`, and no longer refers to missing built-in scopes. The equivalent scope was applied to this isolated realm through the loopback admin API; subsequent browser token contained `sub`, and `/me` succeeded. This does not provide profile/email claims: user discovery currently returned `email=null` and `displayName=null`. Add explicit profile/email mappers or valid scopes if those fields are needed.

## API and playback results

All statuses below were observed during this live run:

| Check | Result |
|---|---:|
| Authenticated Gateway `GET /api/v1/identity/me` | 200 |
| Authenticated direct identity-service `GET /api/identity/me` | 200 |
| Authenticated membership discovery before provisioning | 200, empty list |
| Anonymous Gateway `/api/v1/identity/me` | 401 |
| Anonymous identity-service `/api/identity/me` | 401 |
| Modified-signature token at Gateway and identity-service | 401 / 401 |
| Anonymous Gateway broadcast management | 401 |
| Anonymous direct Stream-service broadcast management | 401 |
| PUBLIC playback token endpoint | 200 |
| PRIVATE playback token endpoint | 401 |
| PRIVATE media request without a grant | 401 |
| Anonymous PUBLIC watch page | Stayed on permanent `/watch/{publicId}` route; token 200; playlist and dummy segment 200 |
| Anonymous PRIVATE watch page | Stayed on permanent watch route; token 401; player displayed private message; no media request |

One synthetic tenant and one active `VIEWER` membership were inserted only into the isolated identity database. Authenticated membership discovery then returned one entry with role `VIEWER`; selecting that member tenant returned 200, and selecting a different unassigned tenant returned 404. This exercised lookup for a provisioned membership, not an invitation or self-service provisioning workflow. No tenant provisioning API currently exists.

The public playlist was fetched through the authenticated HLS proxy path using its short-lived grant. Its segment reference was rewritten to the protected `/broadcast-play/...` path and the synthetic segment request returned 200. The grant value is deliberately omitted from logs and this report. These checks validate the proxy authorization path against a stub origin only; they do not establish real MediaMTX authentication or video decoding.

## Historical tests not repeated

- The following focused Phase 3.2A security methods had previously passed and were not rerun during this live integration task:

| Module / class | Previously passed methods |
|---|---|
| common-security — `OidcJwtValidatorsTest` | `acceptsTokenWithExpectedIssuerAudienceAndLifetime`; `rejectsWrongIssuer`; `rejectsMissingAudience`; `rejectsExpiredToken`; `rejectsTokenBeforeNotBeforeTime` |
| common-security — `OidcJwtSignatureTest` | `validatesRsaSignatureAndRejectsModifiedSignature` |
| common-security — `MembershipResolverTest` | `tenantSelectorDoesNotCreateMembership`; `resolvedTenantContextComesFromMembership` |
| camera-gateway — `StreamingSecurityConfigurationTest` | `requiresAuthenticationForManagementRoutes`; `authenticatedJwtCanReachManagementRoutes`; `deniesLegacyMediaAndWebSocketRoutes`; `permitsPublicPlaybackRoutesToReachBroadcastPolicy` |
| camera-gateway — `LegacySecurityConfigurationTest` | `preservesUnauthenticatedTrustedSurveillanceGatewayAccess` |
| stream-service — `StreamingResourceSecurityConfigurationTest` | `requiresAuthenticationForManagementRoutes`; `permitsPlaybackPolicyRoutesAndRequiresTheirOwnGrant` |
| identity-service — `IdentityControllerSecurityTest` | `rejectsAnonymousIdentityDiscovery`; `returnsMembershipsForValidatedJwtPrincipal`; `returnsDiscoveredUserFromAuthenticatedIdentity` |
| identity-service — `RolePermissionEvaluatorTest` | `deniesPermissionWhenTenantMembershipDoesNotExist`; `viewerCanViewPlaybackButCannotManageBroadcasts` |

Historical count: **20 focused methods PASS**. Gateway's pre-existing `GatewayApplicationTests.contextLoads` also passed in that earlier validation. The accidental broad stream-service test invocation and its historical 24 method results remain recorded in the then-current `docs/saas/PHASE_3_2A_VALIDATION.md` history/commit; those tests were not run in this task.
- The previously passed Broadcast E2E, MediaMTX authentication and load tests were not rerun.
- No full Gradle regression, browser Broadcast E2E, Docker image build, or load test ran.
- `bootJar` compilation and the configuration-only Compose check are current-run validation, not test executions.

## Remaining blockers and limitations

1. **Real camera pipeline: NOT RUN.** No camera registration, RTSP connection, ingest-agent, MediaMTX publish/read, FFmpeg process or decoded browser video was tested in this identity-focused run.
2. **Token expiry/revocation: NOT RUN live.** This run checked token lifetime claims and invalid-signature rejection. The earlier unit checks for expiry are historical; an expired live token and Keycloak logout/revocation behavior were not exercised here.
3. **Identity profile claims:** email and display name are currently absent from the access token and stored as null. The realm should add the needed claims explicitly if the application requires them.
4. **Membership provisioning:** the migration and read APIs work, but the only provisioning was a direct insert into the isolated test DB. Product-grade tenant creation, invitation, membership administration and revocation are outstanding.
5. **Authorization coverage:** management endpoints reject anonymous users and the identity membership selector enforces membership. Tenant ownership checks for Camera, Stream, Broadcast, Recording and Analytics are not implemented by this identity foundation.
6. **Browser security posture:** this validates local HTTP development origins only. Production requires HTTPS, production issuer/audience/callback values, private administrative ingress, credential rotation, and an explicit token storage/session policy review.

## Final status

**OIDC live login, issuer/audience/JWKS validation, identity migration, `/me`, membership lookup, anonymous management denial, and PUBLIC/PRIVATE playback policy: PASS in isolated local services.**

**Phase 3.2A.1 as a complete acceptance package: PARTIAL PASS.** The user requested live OIDC integration, which was observed; the real RTSP-to-video pipeline, live expiry/revocation, and invitation provisioning are not prerequisites of the identity smoke but remain unverified. Do not describe this as a production-ready Streaming SaaS deployment.
