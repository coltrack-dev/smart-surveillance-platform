# Organization Management API — Phase 3.3A

All API paths below are Gateway paths in Streaming mode. They require an OIDC access token validated by the Streaming Gateway and Identity Service. The Gateway rewrites them to Identity Service `/api/organizations/**` and `/api/invitations/accept`. Legacy Surveillance explicitly denies these API paths while leaving existing trusted Surveillance routes unchanged; no new service is added to Surveillance Compose. `tenantId` is a resource selector; active membership is resolved from the authenticated issuer+subject for every call.

## Organization endpoints

| Method and path | Purpose | Permission |
|---|---|---|
| `POST /api/v1/organizations` | Create organization and active OWNER membership transactionally | Authenticated user |
| `GET /api/v1/organizations` | List caller's active organizations | Authenticated user |
| `GET /api/v1/organizations/{tenantId}` | Read organization | Active member |
| `PATCH /api/v1/organizations/{tenantId}` | Rename organization | OWNER |
| `GET /api/v1/organizations/{tenantId}/members` | List active members | Active member |
| `PATCH /api/v1/organizations/{tenantId}/members/{userId}` | Change role | OWNER; ADMIN only for OPERATOR/VIEWER targets and roles |
| `DELETE /api/v1/organizations/{tenantId}/members/{userId}` | Revoke active membership | OWNER; ADMIN only OPERATOR/VIEWER |
| `POST /api/v1/organizations/{tenantId}/leave` | Revoke caller's own membership | Active member, last OWNER protected |
| `POST /api/v1/organizations/{tenantId}/invitations` | Create invitation | OWNER; ADMIN may invite OPERATOR/VIEWER |
| `GET /api/v1/organizations/{tenantId}/invitations` | List pending, unexpired invitations | OWNER or ADMIN |
| `DELETE /api/v1/organizations/{tenantId}/invitations/{invitationId}` | Revoke invitation | OWNER; ADMIN only lower-role invites |
| `POST /api/v1/invitations/accept` | Accept invitation after login | Authenticated JWT with verified matching email |

Create example:

```http
POST /api/v1/organizations
Authorization: Bearer <OIDC access token>
Content-Type: application/json

{"name":"Acme Operations","slug":"acme-operations"}
```

The server generates the UUID and assigns the creator OWNER in the same transaction. Slugs are lowercase alphanumeric hyphen-separated identifiers, max 63 characters. Names are max 120 characters. Existing tenant UUIDs are never accepted as creation input.

Role change example: `{"role":"VIEWER"}`. Roles are OWNER, ADMIN, OPERATOR, VIEWER. The last ACTIVE OWNER cannot be demoted, removed or leave; other members must first be promoted to OWNER. If the actor lacks permission or is not an active member, the API returns 403 or non-disclosing 404 as appropriate. Duplicate slug or active membership conflicts return 409. Invalid input returns 400; unauthenticated access returns 401.

## Invitation lifecycle

Create with `{"email":"person@example.test","role":"OPERATOR"}`. Email is trimmed and lowercased. The invitation expires after seven days. A cryptographically random 256-bit URL-safe token is returned only on creation when `identity.invitations.development-link-enabled=true`; otherwise `invitationUrl` is null and the response contains no token. The development URL puts the token in a URL fragment (`/invite#token=...`), which is not sent in the HTTP request. The option rejects any non-loopback or non-HTTP base URL and must remain disabled outside local development. No email is sent.

Only SHA-256 token bytes are stored. The token is single use. The accepting JWT must carry `email_verified: true` and an email matching the invited address after normalization. The authenticated `(iss,sub)` maps to the internal User; browser-supplied email/user IDs are never accepted. Unknown invitation tokens are 404; expired/consumed/revoked tokens are 410; email mismatch is 403. Duplicate active membership returns 409. A revoked/suspended membership may be reactivated by a new valid invitation. Revocation and acceptance serialize on the tenant row and invitation row. Expired pending rows are marked EXPIRED when the list or acceptance flow next observes them; no scheduled cleanup worker is included.

Never log the accept request body or invitation URL. Invitation responses are `Cache-Control: no-store`. Member DTOs expose internal user UUID, email, display name, role and join time; they do not expose OIDC issuer/subject, token hashes or Keycloak IDs.

## Migration and rollout

Identity Service Flyway V2 adds `identity.tenant_invitations`, partial pending-email uniqueness, token uniqueness/length validation, expiry and membership indexes, and deferred constraints requiring every tenant to retain an ACTIVE OWNER. Existing organization/membership data is not backfilled or rewritten. V1-to-V2 application and fixture preservation passed against isolated `identity_test_db` on the target PostgreSQL 16.13; see the Phase 3.3A.1 evidence below.

## Known limitations

- No email provider, resend workflow, invitation audit/event log, scheduled expiration worker or operator recovery process.
- PostgreSQL 16.13 database-backed acceptance passed for migration trigger semantics, OWNER races and invitation replay/expiry/email/membership transitions. Cross-node concurrency beyond the single test database instance remains unverified.
- The API is not rate-limited independently at Identity Service. Keep it behind the authenticated, private Gateway.
- No frontend invitation acceptance/dashboard flow yet. Development link is a local handoff contract, not a completed invite page.
- Public ingress remains unapproved; deploy only after migration and persistence tests, logging review and route-level security review.

## Phase 3.3A.1 persistence acceptance (2026-11-24)

**Status: PASS against the Streaming PostgreSQL 16.13 target.** The owner-provided target was verified read-only before writes: `identity_test_db`, owner/role `identity_test_user`, loopback `127.0.0.1:5432`, empty `identity` schema/history. Flyway V1 was applied with Identity Service startup and `SPRING_FLYWAY_TARGET=1`; one synthetic tenant/user/active-OWNER fixture was inserted; normal Identity Service startup applied V2. History contains successful V1 and V2 rows. The original fixture's ID, slug, name, issuer+subject, email, OWNER role and ACTIVE status remained unchanged.

The guarded `IdentityPersistenceAcceptanceTest` passed 11/11 methods against that database. It verified organization transaction and rollback, duplicate slug, scoped membership, ownerless rejection, direct deferred-trigger protection, concurrent removal and self-demotion, invitation hash-at-rest, missing/unverified/mismatched email rejection, expiry, revocation, replay, concurrent acceptance, cross-tenant invitation denial and immediate membership revocation. The final read-only audit found 0 ownerless tenants, 0 failed Flyway migrations, and no leftover test helper function. Acceptance data remains only in the dedicated test database (28 tenants, 14 users, 38 memberships, 8 invitations at final audit); no cleanup was attempted because it would require disabling the owner integrity trigger. No Surveillance or other database was contacted with writes.

The Streaming target is PostgreSQL 16.13; no PostgreSQL 17 acceptance gate is required. No previous unit, Broadcast, Kafka/STOMP, browser or HLS suite was repeated.
