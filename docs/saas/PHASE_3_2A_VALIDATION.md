# Phase 3.2A implementation validation

Date: 2026-11-23. No containers were started, no database was contacted, no Flyway migration was executed, and no production or Surveillance data was accessed.

## Successful focused tests

All methods below have passing JUnit XML for this change:

| Module | Full test class | Methods | Result |
|---|---|---|---:|
| common-security | `com.coltrack.security.OidcJwtValidatorsTest` | acceptsTokenWithExpectedIssuerAudienceAndLifetime; rejectsWrongIssuer; rejectsMissingAudience; rejectsExpiredToken; rejectsTokenBeforeNotBeforeTime | 5/5 PASS |
| common-security | `com.coltrack.security.OidcJwtSignatureTest` | validatesRsaSignatureAndRejectsModifiedSignature | 1/1 PASS |
| common-security | `com.coltrack.identity.security.MembershipResolverTest` | tenantSelectorDoesNotCreateMembership; resolvedTenantContextComesFromMembership | 2/2 PASS |
| camera-gateway | `com.coltrack.gateway.security.StreamingSecurityConfigurationTest` | requiresAuthenticationForManagementRoutes; authenticatedJwtCanReachManagementRoutes; deniesLegacyMediaAndWebSocketRoutes; permitsPublicPlaybackRoutesToReachBroadcastPolicy | 4/4 PASS |
| camera-gateway | `com.coltrack.gateway.security.LegacySecurityConfigurationTest` | preservesUnauthenticatedTrustedSurveillanceGatewayAccess | 1/1 PASS |
| stream-service | `com.coltrack.streamservice.security.StreamingResourceSecurityConfigurationTest` | requiresAuthenticationForManagementRoutes; permitsPlaybackPolicyRoutesAndRequiresTheirOwnGrant | 2/2 PASS |
| identity-service | `com.coltrack.identityservice.controller.IdentityControllerSecurityTest` | rejectsAnonymousIdentityDiscovery; returnsMembershipsForValidatedJwtPrincipal; returnsDiscoveredUserFromAuthenticatedIdentity | 3/3 PASS |
| identity-service | `com.coltrack.identityservice.service.RolePermissionEvaluatorTest` | deniesPermissionWhenTenantMembershipDoesNotExist; viewerCanViewPlaybackButCannotManageBroadcasts | 2/2 PASS |

Total newly added focused methods: **20 PASS, 0 FAIL, 0 SKIPPED**. The existing gateway `GatewayApplicationTests.contextLoads` also ran in the gateway test task and passed (1 existing method). Common-security's 8 passing methods were run with the filtered common-security task; gateway, stream-service and identity-service focused classes were run in module-specific commands, except for the accidental execution documented below.

Gradle was run with one worker and `--no-parallel`. The stream-service and identity-service successes came from these isolated filtered commands:

~~~bash
./gradlew --no-daemon --no-parallel --max-workers=1 \\
  :common:common-security:test \\
  --tests 'com.coltrack.security.OidcJwtValidatorsTest' \\
  --tests 'com.coltrack.security.OidcJwtSignatureTest' \\
  --tests 'com.coltrack.identity.security.MembershipResolverTest'

./gradlew --no-daemon --no-parallel --max-workers=1 \\
  :gateway:camera-gateway:test \\
  --tests 'com.coltrack.gateway.security.StreamingSecurityConfigurationTest' \\
  --tests 'com.coltrack.gateway.security.LegacySecurityConfigurationTest'

./gradlew --no-daemon --no-parallel --max-workers=1 \\
  :services:stream-service:test \\
  --tests 'com.coltrack.streamservice.security.StreamingResourceSecurityConfigurationTest'

./gradlew --no-daemon --no-parallel --max-workers=1 \\
  :services:identity-service:test \\
  --tests 'com.coltrack.identityservice.controller.IdentityControllerSecurityTest' \\
  --tests 'com.coltrack.identityservice.service.RolePermissionEvaluatorTest'
~~~

The common-security results came from a filtered task in a multi-task invocation; its report contains only the three selected test classes (8/8 PASS). Gateway's selected classes passed in the later multi-task invocation, which also ran the pre-existing `GatewayApplicationTests.contextLoads` method; it passed. Because Gradle did not scope those filters consistently across multiple tasks, these are test selections, not a claim that each displayed Gradle block was run as an independent command.

## Accidental broad stream-service run

An invocation that supplied several Gradle `Test` tasks in one command did not apply `--tests` filters uniformly: `:services:stream-service:test` ran every test class. This was unintentional. The two newly added methods initially failed because the MVC test slice did not register its nested probe controller. The 24 pre-existing stream-service methods all passed during this run, but those tests were deliberately not repeated after the fixture fix. Only the two-method new security class was rerun in a separate filtered Gradle command; it passed 2/2.

The 24 repeated methods are the methods listed for `stream-service` in `docs/TEST_INVENTORY.md` (MediaHlsClientTest 2, IngestAgentPropertiesTest 1, RtspStreamPropertiesTest 1, BroadcastPlaybackControllerTest 4, BroadcastServiceTest 3, PlaybackTokenServiceTest 1, StreamManagerLeaseTest 4, CameraStreamWorkerTest 4, IngestAgentStreamWorkerTest 4). They were all PASS in the observed XML before the later focused run replaced the stream-service XML files. No other module's historical regression suite was run.

Two earlier attempts also stopped before all focused tests completed: first, common-security's test runtime had an unaligned JUnit Platform launcher; adding the aligned launcher fixed discovery. Then one not-before test used a timestamp within Spring Security's default clock skew; moving it beyond the allowed skew fixed the assertion. The initial gateway test slice omitted its probe controller; explicitly importing it fixed the 404 fixture failures. These were corrected and the focused classes above passed.

## Static validation and frontend

- `docker compose --env-file .env.example -f docker-compose.demo.yml config -q` — PASS.
- `docker compose --env-file .env.example -f docker-compose.streaming.yml config -q` — PASS with temporary local-only values supplied for required secrets. Neither command starts services.
- `npm run build` in `frontend/` — PASS; `vue-tsc -b` and Vite production build completed. Vite reported the existing/main JavaScript bundle exceeds 500 kB minified; build succeeded.
- `git diff --check` — PASS after normalizing line endings in the edited Gradle settings and removing trailing blank lines.

## Not run / remaining validation

- Keycloak container startup, realm import, live OIDC login, real JWKS validation and browser callback — NOT RUN.
- Flyway identity migration, PostgreSQL integration and membership provisioning — NOT RUN.
- Full browser E2E, authenticated HLS, PRIVATE playback authorization, iframe login, refresh/revocation under live playback — NOT RUN.
- Docker image builds or any Compose `up` — NOT RUN.
- Existing Surveillance runtime smoke test — NOT RUN; existing containers/data were not touched.

Current result: **OIDC identity foundation code and focused test slices PASS; live integration and public-readiness remain unverified.** Streaming remains loopback-only.
