package com.coltrack.identityservice.service;

import com.coltrack.identity.model.User;
import com.coltrack.identity.security.AuthenticatedPrincipal;
import com.coltrack.identityservice.model.OrganizationDtos.*;
import com.coltrack.identityservice.repository.IdentityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in PostgreSQL acceptance. It refuses to run unless the explicitly named
 * test target is selected and confirms database, role and server before writes.
 */
@EnabledIfEnvironmentVariable(named = "IDENTITY_ACCEPTANCE_TARGET", matches = "identity_test_db")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.flyway.enabled=false",
        "app.security.oidc.issuer-uri=https://identity-test.invalid/realms/test",
        "app.security.oidc.jwk-set-uri=https://identity-test.invalid/realms/test/protocol/openid-connect/certs",
        "app.security.oidc.audience=identity-test",
        "identity.invitations.development-link-enabled=true",
        "identity.invitations.public-base-url=http://localhost:5173"
})
class IdentityPersistenceAcceptanceTest {
    private static final String ISSUER = "https://phase33a1.test/issuer";
    private static final UUID V1_TENANT = UUID.fromString("33000000-0000-4000-8000-000000000002");

    @Autowired JdbcTemplate jdbc;
    @Autowired IdentityRepository identities;
    @Autowired OrganizationManagementService organizations;
    @Autowired IdentityDirectory directory;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired DataSource dataSource;

    @DynamicPropertySource
    static void targetDatabase(DynamicPropertyRegistry properties) {
        String target = System.getenv("IDENTITY_ACCEPTANCE_TARGET");
        String url = System.getenv("IDENTITY_ACCEPTANCE_JDBC_URL");
        String username = System.getenv("IDENTITY_ACCEPTANCE_DB_USER");
        if (!"identity_test_db".equals(target)
                || !"jdbc:postgresql://127.0.0.1:5432/identity_test_db".equals(url)
                || !"identity_test_user".equals(username)) {
            throw new IllegalStateException("Acceptance tests are locked to identity_test_db at 127.0.0.1:5432 as identity_test_user");
        }
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> username);
        properties.add("spring.datasource.password", () -> System.getenv().getOrDefault("IDENTITY_ACCEPTANCE_DB_PASSWORD", ""));
    }

    @BeforeEach
    void verifyExactDatabaseBeforeWrites() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT current_database(), current_user, version()")) {
            assertTrue(result.next());
            assertEquals("identity_test_db", result.getString(1));
            assertEquals("identity_test_user", result.getString(2));
            assertTrue(result.getString(3).contains("PostgreSQL 16.13"), result.getString(3));
        }
    }

    @Test
    void flywayV1UpgradeToV2PreservesExistingSyntheticTenantAndMembership() {
        List<String> versions = jdbc.query("SELECT version FROM identity.flyway_identity_schema_history WHERE success ORDER BY installed_rank",
                (rs, row) -> rs.getString(1));
        assertTrue(versions.containsAll(List.of("1", "2")), versions.toString());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM identity.tenants t JOIN identity.tenant_memberships m ON m.tenant_id=t.id JOIN identity.users u ON u.id=m.user_id WHERE t.id=? AND t.slug=? AND u.subject=? AND m.role_code='OWNER' AND m.status='ACTIVE'",
                Integer.class, V1_TENANT, "phase33a1-upgrade-fixture", "upgrade-fixture"));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgname IN ('ct_identity_membership_active_owner','ct_identity_tenant_active_owner') AND tgdeferrable AND tginitdeferred", Integer.class));
    }

    @Test
    void organizationAndFirstOwnerCommitTogetherAndTenantRenameIsScoped() {
        AuthenticatedPrincipal owner = principal("create-owner", "create-owner@example.test", true);
        Organization tenant = createOrganization(owner, unique("create"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM identity.tenant_memberships WHERE tenant_id=? AND user_id=(SELECT id FROM identity.users WHERE issuer=? AND subject=?) AND role_code='OWNER' AND status='ACTIVE'",
                Integer.class, tenant.id(), ISSUER, owner.subject()));

        Organization renamed = organizations.update(owner, tenant.id(), new UpdateOrganization("Renamed Org"));
        assertEquals("Renamed Org", renamed.name());
        assertTrue(directory.findMembership(owner, tenant.id()).isPresent());

        AuthenticatedPrincipal otherOwner = principal("other-owner", "other-owner@example.test", true);
        Organization other = createOrganization(otherOwner, unique("other"));
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> organizations.get(owner, other.id())).getStatusCode().value());
        assertTrue(directory.findMembership(owner, other.id()).isEmpty());
    }

    @Test
    void databaseRejectsCommittedTenantWithoutActiveOwner() {
        UUID id = UUID.randomUUID();
        RuntimeException failure = assertThrows(RuntimeException.class, () -> new TransactionTemplate(transactionManager)
                .execute(status -> jdbc.update("INSERT INTO identity.tenants(id,slug,display_name) VALUES (?,?,?)",
                        id, unique("ownerless"), "Ownerless")));
        assertEquals("23514", sqlState(failure));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM identity.tenants WHERE id=?", Integer.class, id));
    }

    @Test
    void ownerInsertFailureRollsBackOrganizationCreation() {
        AuthenticatedPrincipal principal = principal("rollback-owner", "rollback@example.test", true);
        String slug = unique("rollback");
        jdbc.execute("""
                CREATE FUNCTION identity.test_phase33a_reject_owner() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  IF NEW.role_code='OWNER' THEN
                    RAISE EXCEPTION 'synthetic owner insert failure' USING ERRCODE='23514';
                  END IF;
                  RETURN NEW;
                END;
                $$
                """);
        jdbc.execute("CREATE TRIGGER test_phase33a_reject_owner BEFORE INSERT ON identity.tenant_memberships FOR EACH ROW EXECUTE FUNCTION identity.test_phase33a_reject_owner()");
        try {
            assertThrows(RuntimeException.class, () -> organizations.create(principal, new CreateOrganization("Rollback", slug)));
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS test_phase33a_reject_owner ON identity.tenant_memberships");
            jdbc.execute("DROP FUNCTION IF EXISTS identity.test_phase33a_reject_owner()");
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM identity.tenants WHERE slug=?", Integer.class, slug));
    }

    @Test
    void duplicateSlugReturnsConflictAndDoesNotCreateSecondTenant() {
        String slug = unique("duplicate");
        createOrganization(principal("slug-first", "slug-first@example.test", true), slug);
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> organizations.create(principal("slug-second", "slug-second@example.test", true),
                        new CreateOrganization("Second", slug)));
        assertEquals(409, error.getStatusCode().value());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM identity.tenants WHERE slug=?", Integer.class, slug));
    }

    @Test
    void deferredConstraintRejectsRevokingLastOwnerDirectly() {
        AuthenticatedPrincipal owner = principal("last-owner", "last-owner@example.test", true);
        Organization tenant = createOrganization(owner, unique("last-owner"));
        UUID ownerId = identities.findUser(ISSUER, owner.subject()).orElseThrow().id();
        RuntimeException failure = assertThrows(RuntimeException.class, () -> new TransactionTemplate(transactionManager)
                .execute(status -> jdbc.update("UPDATE identity.tenant_memberships SET status='REVOKED' WHERE tenant_id=? AND user_id=?",
                        tenant.id(), ownerId)));
        assertEquals("23514", sqlState(failure));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM identity.tenant_memberships WHERE tenant_id=? AND user_id=? AND role_code='OWNER' AND status='ACTIVE'",
                Integer.class, tenant.id(), ownerId));
    }

    @Test
    void concurrentRemovalOfTwoOwnersCannotRemoveBoth() throws Exception {
        OwnerPair owners = createTwoOwners("remove-race");
        List<Integer> outcomes = concurrently(
                () -> { organizations.remove(owners.first(), owners.tenant().id(), owners.secondUserId()); return null; },
                () -> { organizations.remove(owners.second(), owners.tenant().id(), owners.firstUserId()); return null; });
        assertEquals(1, outcomes.stream().filter(status -> status == 0).count(), outcomes.toString());
        assertTrue(outcomes.stream().anyMatch(status -> status == 404 || status == 403 || status == 409), outcomes.toString());
        assertTrue(activeOwnerCount(owners.tenant().id()) >= 1);
    }

    @Test
    void concurrentOwnerSelfDemotionCannotRemoveBothOwners() throws Exception {
        OwnerPair owners = createTwoOwners("demote-race");
        List<Integer> outcomes = concurrently(
                () -> organizations.changeRole(owners.first(), owners.tenant().id(), owners.firstUserId(), new ChangeRole("ADMIN")),
                () -> organizations.changeRole(owners.second(), owners.tenant().id(), owners.secondUserId(), new ChangeRole("ADMIN")));
        assertEquals(1, outcomes.stream().filter(status -> status == 0).count(), outcomes.toString());
        assertTrue(outcomes.stream().anyMatch(status -> status == 403 || status == 409), outcomes.toString());
        assertTrue(activeOwnerCount(owners.tenant().id()) >= 1);
    }

    @Test
    void invitationStoresOnlyHashRequiresVerifiedMatchingEmailAndRejectsReplay() throws Exception {
        AuthenticatedPrincipal owner = principal("invite-owner", "invite-owner@example.test", true);
        Organization tenant = createOrganization(owner, unique("invite"));
        Invitation invitation = organizations.invite(owner, tenant.id(), new CreateInvitation("new-member@example.test", "VIEWER"));
        String token = token(invitation);
        byte[] raw = java.util.Base64.getUrlDecoder().decode(token);
        byte[] expectedHash = MessageDigest.getInstance("SHA-256").digest(raw);
        byte[] storedHash = jdbc.queryForObject("SELECT token_hash FROM identity.tenant_invitations WHERE id=?", byte[].class, invitation.id());
        assertArrayEquals(expectedHash, storedHash);
        assertEquals(32, storedHash.length);

        assertEquals(403, assertThrows(ResponseStatusException.class, () -> organizations.accept(
                principal("unverified", "new-member@example.test", false), new AcceptInvitation(token)))
                .getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> organizations.accept(
                principal("missing-email", null, false), new AcceptInvitation(token)))
                .getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> organizations.accept(
                principal("wrong-email", "different@example.test", true), new AcceptInvitation(token)))
                .getStatusCode().value());

        AuthenticatedPrincipal member = principal("accepted-member", "new-member@example.test", true);
        AcceptedMembership accepted = organizations.accept(member, new AcceptInvitation(token));
        assertEquals(tenant.id(), accepted.tenantId());
        assertEquals(410, assertThrows(ResponseStatusException.class,
                () -> organizations.accept(member, new AcceptInvitation(token))).getStatusCode().value());
        assertEquals("ACCEPTED", jdbc.queryForObject("SELECT status FROM identity.tenant_invitations WHERE id=?", String.class, invitation.id()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM identity.tenant_memberships WHERE tenant_id=? AND user_id=? AND status='ACTIVE'",
                Integer.class, tenant.id(), accepted.userId()));
    }

    @Test
    void concurrentAcceptanceIsSingleUseAndMembershipRevocationIsImmediate() throws Exception {
        AuthenticatedPrincipal owner = principal("accept-owner", "accept-owner@example.test", true);
        Organization tenant = createOrganization(owner, unique("accept-race"));
        Invitation invitation = organizations.invite(owner, tenant.id(), new CreateInvitation("race-member@example.test", "OPERATOR"));
        String token = token(invitation);
        AuthenticatedPrincipal member = principal("race-member", "race-member@example.test", true);
        List<Integer> outcomes = concurrently(
                () -> organizations.accept(member, new AcceptInvitation(token)),
                () -> organizations.accept(member, new AcceptInvitation(token)));
        assertEquals(1, outcomes.stream().filter(status -> status == 0).count(), outcomes.toString());
        assertEquals(1, outcomes.stream().filter(status -> status == 410).count(), outcomes.toString());
        User memberUser = identities.findUser(ISSUER, member.subject()).orElseThrow();
        assertTrue(directory.findMembership(member, tenant.id()).isPresent());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> organizations.invitations(principal("non-member", "non-member@example.test", true), tenant.id()))
                .getStatusCode().value());

        organizations.remove(owner, tenant.id(), memberUser.id());
        assertTrue(directory.findMembership(member, tenant.id()).isEmpty());
    }

    @Test
    void expiredAndRevokedInvitationsAreRejected() {
        AuthenticatedPrincipal owner = principal("expiry-owner", "expiry-owner@example.test", true);
        Organization tenant = createOrganization(owner, unique("expiry"));

        Invitation expired = organizations.invite(owner, tenant.id(), new CreateInvitation("expired@example.test", "VIEWER"));
        String expiredToken = token(expired);
        jdbc.update("UPDATE identity.tenant_invitations SET expires_at=now()-interval '1 minute' WHERE id=?", expired.id());
        assertEquals(410, assertThrows(ResponseStatusException.class, () -> organizations.accept(
                principal("expired-user", "expired@example.test", true), new AcceptInvitation(expiredToken)))
                .getStatusCode().value());
        assertEquals("EXPIRED", jdbc.queryForObject("SELECT status FROM identity.tenant_invitations WHERE id=?", String.class, expired.id()));

        Invitation revoked = organizations.invite(owner, tenant.id(), new CreateInvitation("revoked@example.test", "VIEWER"));
        String revokedToken = token(revoked);
        organizations.revokeInvitation(owner, tenant.id(), revoked.id());
        assertEquals(410, assertThrows(ResponseStatusException.class, () -> organizations.accept(
                principal("revoked-user", "revoked@example.test", true), new AcceptInvitation(revokedToken)))
                .getStatusCode().value());
    }

    private Organization createOrganization(AuthenticatedPrincipal owner, String slug) {
        Organization organization = organizations.create(owner, new CreateOrganization("Acceptance " + slug, slug));
        return organization;
    }

    private OwnerPair createTwoOwners(String prefix) {
        AuthenticatedPrincipal first = principal(prefix + "-a", prefix + "-a@example.test", true);
        AuthenticatedPrincipal second = principal(prefix + "-b", prefix + "-b@example.test", true);
        Organization tenant = createOrganization(first, unique(prefix));
        User secondUser = identities.discover(ISSUER, second.subject(), second.email(), true, second.displayName());
        jdbc.update("INSERT INTO identity.tenant_memberships(tenant_id,user_id,role_code,status) VALUES (?,?, 'OWNER','ACTIVE')",
                tenant.id(), secondUser.id());
        return new OwnerPair(tenant, first, second, identities.findUser(ISSUER, first.subject()).orElseThrow().id(), secondUser.id());
    }

    private List<Integer> concurrently(Callable<?> first, Callable<?> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<?> operation : List.of(first, second)) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start barrier timed out");
                    try { operation.call(); return 0; }
                    catch (ResponseStatusException ex) { return ex.getStatusCode().value(); }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            return List.of(futures.get(0).get(30, TimeUnit.SECONDS), futures.get(1).get(30, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private int activeOwnerCount(UUID tenantId) {
        return jdbc.queryForObject("SELECT count(*) FROM identity.tenant_memberships WHERE tenant_id=? AND status='ACTIVE' AND role_code='OWNER'",
                Integer.class, tenantId);
    }

    private AuthenticatedPrincipal principal(String subject, String email, boolean verified) {
        return new AuthenticatedPrincipal(ISSUER, subject, email, verified, subject);
    }

    private static String token(Invitation invitation) {
        assertNotNull(invitation.invitationUrl());
        return invitation.invitationUrl().substring(invitation.invitationUrl().indexOf("#token=") + "#token=".length());
    }

    private static String unique(String prefix) { return "p33a1-" + prefix + "-" + UUID.randomUUID().toString().substring(0, 8); }

    private static String sqlState(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException) return sqlException.getSQLState();
        }
        return null;
    }

    private record OwnerPair(Organization tenant, AuthenticatedPrincipal first, AuthenticatedPrincipal second,
                             UUID firstUserId, UUID secondUserId) {}
}
