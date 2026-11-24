# Phase 3.2A — OIDC identity foundation

**Streaming database target:** PostgreSQL 16.13 (`postgres:16.13-alpine`) for both application identity data and the separate Keycloak database. Keep Flyway migrations compatible with PostgreSQL 16. Identity V1 and V2 have passed the isolated PostgreSQL 16.13 migration and persistence acceptance; see `ORGANIZATION_MANAGEMENT_API.md` and `../TEST_INVENTORY.md`.

## Implementation status

The initial OIDC foundation is implemented for Streaming only. `docker-compose.streaming.yml` adds Keycloak `26.8.2` with a dedicated PostgreSQL database and persistent volume, a development realm import, and a public Vue OIDC client using Authorization Code + PKCE (S256). The Keycloak HTTP port is bound to loopback. The browser client is public and contains no client secret. Do not use `start-dev`, the sample realm callback origins, or development credentials in production.

Streaming gateway, stream-service and identity-service validate access-token signature through the configured JWKS endpoint, exact issuer, `streaming-api` audience, expiration and not-before. Required OIDC settings are validated when the resource-server decoder is created; an incomplete OIDC configuration prevents service startup. Gateway management routes require a bearer token. Gateway permits only the existing playback policy routes to reach stream-service anonymously; Broadcast playback remains governed there by enabled state and explicit `PUBLIC` visibility. Gateway denies direct `/media-hls/**`, `/hls/**` and `/ws/**` access in OIDC mode. The Streaming gateway and player remain loopback-bound in the development Compose file.

Surveillance retains the gateway's legacy permit-all configuration and existing deployment dependencies. Keycloak and identity-service are absent from demo Compose. Streaming uses a dedicated database/volume rather than the prior shared/external database default; no existing Camera, Stream or Broadcast rows are copied or migrated by this change.

Identity tables live in `identity` schema and are migrated only by identity-service Flyway history `flyway_identity_schema_history`. The first authenticated call to `/api/v1/identity/me` upserts a user keyed by `(issuer, subject)`. Membership discovery is application-owned; Keycloak groups are not consulted. `/api/v1/identity/me/memberships` returns active memberships and `/api/v1/identity/tenants/{tenantId}/membership` resolves only a validated active membership, returning 404 otherwise. Phase 3.3A adds organization and invitation management to this same identity authority; memberships remain application-owned and are checked on each operation.

Organization creation requires an authenticated OIDC principal and creates the tenant plus its active OWNER membership in one transaction. Membership and invitation changes lock the tenant row, so role changes, removal, leave and invitation acceptance serialize per organization. No API accepts a browser-supplied user ID, owner assignment or tenant ownership claim. Invitation acceptance uses the authenticated verified email and issuer+subject-derived internal user; Keycloak groups are not consulted.

`common-security` provides the provider-neutral principal, tenant-context, membership-resolver and permission-evaluator interfaces. The initial role evaluator is a foundation only: Camera, Stream and Broadcast records have no tenant owner yet, and their APIs do not enforce these role checks. Do not describe this release as tenant isolation or public SaaS readiness.

### Local development configuration

Copy `.env.example` to an untracked `.env` and provide unique local-only values for the Streaming database, Keycloak database/admin, agent, camera encryption, MediaMTX and playback secrets. Generate secret values locally, for example with `openssl rand -hex 32`; encode the camera credential key as base64 of 32 random bytes. Never commit `.env` or paste production secrets into the repository. A local Keycloak administrator username may be `dev-admin`; generate its password rather than using a committed default. Set `STREAMING_OIDC_BASE_URL=http://localhost:8180`, `STREAMING_OIDC_ISSUER_URI=http://localhost:8180/realms/streaming`, `STREAMING_OIDC_REDIRECT_URI=http://localhost:5173/`, and the matching local player origin. The realm import permits localhost and 127.0.0.1 on port 5173. If those origins change, update the imported client and rebuild the frontend. Keycloak admin UI is available only at loopback port 8180. The SPA uses client `streaming-vue`; audience is `streaming-api`.

The imported realm deliberately has no user or password. Create a local test user through the loopback-only admin UI and use the realm's normal account flow. Tenant membership must still be provisioned in the isolated `identity` schema by a reviewed local-only procedure; authentication alone grants no tenant access.

No containers were started as part of the identity foundation validation. Static Compose validation and focused test results, including unrun live-integration checks, are recorded in [`PHASE_3_2A_VALIDATION.md`](PHASE_3_2A_VALIDATION.md).

---

# Phase 3.1 — Identity and authorization architecture

## Decision

Adopt standard OIDC authentication with Spring Security OAuth2 Resource Server validation in the gateway and resource services. For the first self-hosted SaaS deployment, prefer Keycloak as the OIDC provider; keep provider integration behind an identity adapter so a managed OIDC provider can replace it later. Do not build a bespoke password/JWT/refresh-token system and do not add Keycloak to Surveillance Compose.

This adds an operational component, but avoids owning password storage, recovery, MFA, refresh rotation and login-abuse defenses. If Keycloak operations are not viable, use a managed OIDC provider with the same protocol boundary. TenantMembership remains application data; IdP groups are not the authorization database.

## Existing boundary found in repository

There is no Spring Security, OAuth2 Resource Server, JWT, or OIDC dependency/configuration in gateway, camera-service, stream-service or websocket-service. BroadcastController manually compares one global BROADCAST_MANAGEMENT_TOKEN. Playback uses a separate short-lived HMAC token. The Rust ingest-agent uses an optional static Bearer token; when AGENT_API_TOKEN is absent, its pipeline API accepts unauthenticated requests. Gateway routes Camera, Stream and WebSocket APIs without authentication. Detailed route inventory is in SECURITY_BOUNDARIES.md.

## Identity entities

- User: internal UUID, external issuer+subject pair with a unique constraint, optional verified email/display metadata, enabled/deleted state and timestamps. Email is mutable and is not the identity key. If OIDC is authoritative, passwords stay at the IdP.
- Tenant/Organization: UUID, stable slug, display name, lifecycle status and timestamps.
- TenantMembership: tenant UUID, user UUID, fixed role code, status, invited/activated timestamps; unique tenant+user. Users can be members of many organizations.
- Role: initially OWNER, ADMIN, OPERATOR and VIEWER, centrally mapped to explicit permissions. Do not permit customer-defined privilege-bearing roles in the MVP.
- Session/Refresh Token: use IdP sessions and refresh tokens. If a first-party session broker later becomes necessary, store only a hash of a random opaque rotating token, with expiry, family/reuse detection and revocation metadata.

Map the OIDC issuer+subject to an internal User UUID. Never use email or a browser-supplied tenant ID as proof of identity/access.

## Browser and service authentication

1. Vue uses Authorization Code with PKCE. No client secret belongs in browser code. Prefer an HTTP-only, Secure, SameSite cookie BFF if frontend hosting later adopts a same-origin backend; until then keep short-lived access tokens in memory and avoid localStorage.
2. Validate issuer, signature/JWKS, audience, expiry and not-before at gateway and each service. Gateway policy does not replace object-level checks in Camera/Stream services.
3. A selected tenant ID from URL/header is only a selector. Resolve active membership for the authenticated user and authorize role on each request or a short-lived revocable membership cache.
4. Create immutable TenantContext at the trusted boundary; pass it explicitly through service and repository calls. Clear request context at completion. Missing context in Streaming mode fails closed.
5. Internal service calls use workload credentials or short-lived audience-specific service tokens. Never forward browser JWTs or MediaMTX credentials to ingest-agent.

Coarse token scopes may distinguish camera.read, camera.manage, broadcast.manage and playback. Membership roles remain the source for tenant-specific permissions: OWNER administers tenant/members, ADMIN manages resources, OPERATOR operates them, VIEWER has permitted read/playback access. Resolve exact matrix before implementation.

## Broadcast and playback policy

Replace Broadcast's shared management secret with user authentication and tenant membership/role checks. During a private-only migration window, legacy secret support may remain behind private ingress with a removal date; it must never identify a user or tenant.

Public playback remains a separate capability:
- Anonymous token issue is allowed only for an enabled Broadcast whose owner explicitly enabled public viewing.
- PRIVATE viewing requires a logged-in user with current tenant membership and VIEW permission, or a separately designed expiring/revocable invitation.
- Permanent watch/iframe URLs contain no token. Short-lived playback grants are media-only and bound to broadcast, tenant, audience, expiry and revocation generation.
- Recheck authorization on renewal and every playlist/segment/key fetch. Disable, delete, privacy change, membership removal or invitation revocation denies new requests.
- Continue no-referrer/no-store and redact token query parameters from every proxy and ingress log.

## Options considered

| Option | Benefits | Risks/cost | Decision |
|---|---|---|---|
| App-managed passwords and JWT in Spring | No separate IdP; simple deployment | Team owns recovery, MFA, hashing, abuse defense, refresh rotation, signing keys and security response | Not recommended as default |
| Self-hosted Keycloak / OIDC | Mature account flows, self-hosting, standards-based Spring integration, provider portability | Additional stateful service, patching, backups, availability, keys and realm/client operations | Recommended initial SaaS provider |
| Managed OIDC provider | Low operations, mature identity features, fast onboarding | Vendor/cost/data residency/internet dependence; less suitable for offline self-hosted installs | Valid alternative behind same interface |

## Deployment compatibility

Surveillance remains a trusted single-installation policy and keeps existing behavior; do not add login requirements or tenant checks to its current path as a side effect. Streaming requires OIDC, tenant membership and scoped permissions. Select a policy adapter at composition/configuration time rather than scattering mode branches through domain code. Keep Compose project, database/schema, credentials and issuer separate between modes. A streaming startup missing the required tenant identity configuration should fail closed.

## Decisions needed before 3.2

Confirm OIDC issuer, audience and clients; PKCE/callback origins; access-token TTL and membership revocation window; role matrix; public sharing consent; user invitation/verification; key rotation and account recovery ownership. Then implement on an isolated streaming database and test authentication and authorization before data migration.
