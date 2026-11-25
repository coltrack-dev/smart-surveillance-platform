package com.coltrack.identityservice.service;

import com.coltrack.identity.security.AuthenticatedPrincipal;
import com.coltrack.identityservice.model.OrganizationDtos.CreateOrganization;
import com.coltrack.identityservice.model.QuotaDtos.OrganizationQuotas;
import com.coltrack.identityservice.repository.IdentityRepository;
import com.coltrack.identityservice.repository.QuotaPlanRepository;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Opt-in acceptance test locked to two explicitly named disposable databases on a local PG 16.13 cluster. */
@EnabledIfEnvironmentVariable(named = "IDENTITY_QUOTA_ACCEPTANCE_TARGET", matches = "identity_quota_acceptance_db")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "app.security.oidc.issuer-uri=https://identity-quota-test.invalid/realms/test",
        "app.security.oidc.jwk-set-uri=https://identity-quota-test.invalid/realms/test/protocol/openid-connect/certs",
        "app.security.oidc.audience=identity-quota-test"
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IdentityQuotaPersistenceAcceptanceTest {
    private static final String ISSUER = "https://identity-quota-acceptance.test/issuer";
    private static final Pattern DATABASE_URL_PATTERN = Pattern.compile(
            "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]{1,5}/identity_quota_acceptance_db");
    private static final String DATABASE_USER = "identity_quota_user";

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry properties) {
        String target = System.getenv("IDENTITY_QUOTA_ACCEPTANCE_TARGET");
        String url = System.getenv("IDENTITY_QUOTA_ACCEPTANCE_JDBC_URL");
        String username = System.getenv("IDENTITY_QUOTA_ACCEPTANCE_DB_USER");
        if (!"identity_quota_acceptance_db".equals(target) || url == null
                || !DATABASE_URL_PATTERN.matcher(url).matches()
                || !DATABASE_USER.equals(username)) {
            throw new IllegalStateException("Quota acceptance is locked to identity_quota_acceptance_db on 127.0.0.1 and identity_quota_user");
        }
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> username);
        properties.add("spring.datasource.password", () -> System.getenv().getOrDefault("IDENTITY_QUOTA_ACCEPTANCE_DB_PASSWORD", ""));
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired IdentityRepository identities;
    @Autowired OrganizationManagementService organizations;
    @Autowired OrganizationQuotaService quotaService;
    @Autowired QuotaPlanRepository quotaPlans;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired MockMvc mvc;

    @BeforeEach
    void verifyExactPostgresTarget() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT current_database(), current_user, version()")) {
            assertTrue(result.next());
            assertEquals("identity_quota_acceptance_db", result.getString(1));
            assertEquals(DATABASE_USER, result.getString(2));
            assertTrue(result.getString(3).contains("PostgreSQL 16.13"), result.getString(3));
        }
    }

    @Test
    @Order(1)
    void freshFlywayAppliesV1ThroughV5AndSeedsConstrainedPlanCatalog() {
        assertEquals(List.of("1", "2", "3", "4", "5"), jdbc.query(
                "SELECT version FROM identity.flyway_identity_schema_history WHERE success AND type='SQL' ORDER BY installed_rank",
                (rs, row) -> rs.getString(1)));
        assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM pg_proc WHERE pronamespace='identity'::regnamespace AND proname IN ('is_active_tenant','effective_tenant_quota','resolve_tenant_membership','resolve_active_role')", Integer.class));
        assertEquals(List.of("BUSINESS:50:25:200", "FREE:2:1:3", "PRO:10:5:25"), jdbc.query("""
                SELECT plan_code || ':' || max_cameras || ':' || max_active_broadcasts || ':' || max_concurrent_viewers
                FROM identity.quota_plans ORDER BY plan_code
                """, (rs, row) -> rs.getString(1)));
        assertEquals(8, jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE connamespace='identity'::regnamespace AND conname IN ('quota_plans_plan_code_check','quota_plans_max_cameras_check','quota_plans_max_active_broadcasts_check','quota_plans_max_concurrent_viewers_check','tenant_quota_overrides_max_cameras_check','tenant_quota_overrides_max_active_broadcasts_check','tenant_quota_overrides_max_concurrent_viewers_check','ck_identity_quota_override_not_empty')", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgname IN ('ct_identity_membership_active_owner','ct_identity_tenant_active_owner') AND tgdeferrable AND tginitdeferred", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM identity.tenant_quota_assignments", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM identity.tenant_quota_overrides", Integer.class));
    }

    @Test
    void organizationCreationAssignsDefaultAndLegacyOrganizationFallsBackWithoutBackfill() {
        AuthenticatedPrincipal owner = principal("default-owner");
        var created = organizations.create(owner, new CreateOrganization("Default plan", slug("default")));
        assertEquals("FREE", jdbc.queryForObject("SELECT plan_code FROM identity.tenant_quota_assignments WHERE tenant_id=?",
                String.class, created.id()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM identity.tenant_memberships WHERE tenant_id=? AND role_code='OWNER' AND status='ACTIVE'",
                Integer.class, created.id()));

        jdbc.update("DELETE FROM identity.tenant_quota_assignments WHERE tenant_id=?", created.id());
        OrganizationQuotas fallback = quotaService.get(owner, created.id());
        assertEquals("FREE", fallback.planCode());
        assertEquals(2, fallback.limits().maxCameras());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM identity.tenant_quota_assignments WHERE tenant_id=?",
                Integer.class, created.id()));
    }

    @Test
    void effectiveQuotaUsesPlanAndNullablePerMetricOverrides() {
        var created = organizations.create(principal("override-owner"),
                new CreateOrganization("Override plan", slug("override")));
        jdbc.update("UPDATE identity.tenant_quota_assignments SET plan_code='PRO' WHERE tenant_id=?", created.id());
        jdbc.update("""
                INSERT INTO identity.tenant_quota_overrides(tenant_id,max_cameras,reason)
                VALUES (?, 14, 'synthetic acceptance override')
                """, created.id());

        OrganizationQuotas effective = quotaService.get(principal("override-owner"), created.id());
        assertEquals("PRO", effective.planCode());
        assertEquals(14, effective.limits().maxCameras());
        assertEquals(5, effective.limits().maxActiveBroadcasts());
        assertEquals(25, effective.limits().maxConcurrentViewers());
        assertEquals(14, effective.overrides().maxCameras());
        assertNull(effective.overrides().maxActiveBroadcasts());
        assertNull(effective.overrides().maxConcurrentViewers());
    }

    @Test
    void organizationRollbackLeavesNoQuotaAssignmentAndOwnerInvariantIntact() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("""
                CREATE FUNCTION identity.test_quota_reject_owner() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  IF NEW.role_code='OWNER' THEN
                    RAISE EXCEPTION 'synthetic owner rejection' USING ERRCODE='23514';
                  END IF;
                  RETURN NEW;
                END;
                $$
                """);
        jdbc.execute("CREATE TRIGGER test_quota_reject_owner BEFORE INSERT ON identity.tenant_memberships FOR EACH ROW EXECUTE FUNCTION identity.test_quota_reject_owner()");
        String slug = "quota-rollback-" + suffix;
        try {
            assertThrows(RuntimeException.class, () -> organizations.create(principal("rollback-" + suffix),
                    new CreateOrganization("Rollback", slug)));
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS test_quota_reject_owner ON identity.tenant_memberships");
            jdbc.execute("DROP FUNCTION IF EXISTS identity.test_quota_reject_owner()");
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM identity.tenants WHERE slug=?", Integer.class, slug));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM identity.tenant_quota_assignments a JOIN identity.tenants t ON t.id=a.tenant_id WHERE t.slug=?",
                Integer.class, slug));
    }

    @Test
    void unknownPlanAndMissingConfiguredDefaultFailClosedAndMembershipIsRequired() {
        var created = organizations.create(principal("membership-owner"),
                new CreateOrganization("Membership", slug("membership")));
        jdbc.update("DELETE FROM identity.tenant_quota_assignments WHERE tenant_id=?", created.id());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> quotaPlans.assignDefault(created.id(), "NO_SUCH_PLAN"));
        OrganizationQuotaService invalidDefault = new OrganizationQuotaService(identities, quotaPlans, "NO_SUCH_PLAN");
        assertEquals(503, assertThrows(ResponseStatusException.class,
                () -> invalidDefault.get(principal("membership-owner"), created.id())).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> quotaService.get(principal("not-a-member"), created.id())).getStatusCode().value());
    }

    @Test
    void quotaHttpResponseIsAuthenticatedMemberScopedAndNotCacheable() throws Exception {
        AuthenticatedPrincipal owner = principal("http-owner");
        var created = organizations.create(owner, new CreateOrganization("HTTP quotas", slug("http")));
        mvc.perform(get("/api/organizations/{id}/quotas", created.id()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/organizations/{id}/quotas", created.id())
                        .with(jwt().jwt(token -> token.issuer(ISSUER).subject(owner.subject())
                                .claim("email", owner.email()).claim("email_verified", true))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.planCode").value("FREE"))
                .andExpect(jsonPath("$.limits.maxCameras").value(2));
        mvc.perform(get("/api/organizations/{id}/quotas", created.id())
                        .with(jwt().jwt(token -> token.issuer(ISSUER).subject("unrelated-subject"))))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(7)
    void v2SchemaUpgradeToV5PreservesSyntheticTenantAndDoesNotAssignPlan() {
        String url = System.getenv("IDENTITY_QUOTA_ACCEPTANCE_JDBC_URL")
                .replace("identity_quota_acceptance_db", "identity_quota_v2_db");
        DriverManagerDataSource v2DataSource = new DriverManagerDataSource(url, DATABASE_USER, "");
        Flyway flywayV2 = Flyway.configure().dataSource(v2DataSource).locations("classpath:db/migration")
                .schemas("identity").defaultSchema("identity").table("flyway_identity_schema_history")
                .createSchemas(true).target(MigrationVersion.fromVersion("2")).load();
        flywayV2.migrate();
        JdbcTemplate v2 = new JdbcTemplate(v2DataSource);
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        new TransactionTemplate(new org.springframework.jdbc.support.JdbcTransactionManager(v2DataSource)).execute(status -> {
            v2.update("INSERT INTO identity.tenants(id,slug,display_name) VALUES (?,?,?)", tenantId,
                    "quota-v2-" + tenantId.toString().replace("-", ""), "V2 synthetic tenant");
            v2.update("INSERT INTO identity.users(id,issuer,subject,email,email_verified,display_name) VALUES (?,?,?,?,TRUE,?)",
                    userId, ISSUER, "v2-" + userId, "v2@example.test", "V2 user");
            v2.update("INSERT INTO identity.tenant_memberships(tenant_id,user_id,role_code,status) VALUES (?,?,'OWNER','ACTIVE')",
                    tenantId, userId);
            return null;
        });

        Flyway.configure().dataSource(v2DataSource).locations("classpath:db/migration")
                .schemas("identity").defaultSchema("identity").table("flyway_identity_schema_history")
                .load().migrate();

        assertEquals(List.of("1", "2", "3", "4", "5"), v2.query(
                "SELECT version FROM identity.flyway_identity_schema_history WHERE success AND type='SQL' ORDER BY installed_rank",
                (rs, row) -> rs.getString(1)));
        assertEquals(1, v2.queryForObject("SELECT count(*) FROM identity.tenants WHERE id=?", Integer.class, tenantId));
        assertEquals(1, v2.queryForObject("SELECT count(*) FROM identity.tenant_memberships WHERE tenant_id=? AND user_id=? AND role_code='OWNER' AND status='ACTIVE'",
                Integer.class, tenantId, userId));
        assertEquals(0, v2.queryForObject("SELECT count(*) FROM identity.tenant_quota_assignments WHERE tenant_id=?",
                Integer.class, tenantId));
        assertEquals(0, v2.queryForObject("SELECT count(*) FROM identity.tenant_quota_overrides WHERE tenant_id=?",
                Integer.class, tenantId));
    }

    private AuthenticatedPrincipal principal(String subject) {
        return new AuthenticatedPrincipal(ISSUER, subject, subject + "@example.test", true, subject);
    }

    private static String slug(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "");
    }
}
