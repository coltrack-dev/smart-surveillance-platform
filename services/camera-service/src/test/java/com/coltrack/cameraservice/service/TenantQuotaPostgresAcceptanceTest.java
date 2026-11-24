package com.coltrack.cameraservice.service;

import com.coltrack.identity.quota.TenantQuotaGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in acceptance fixture. Requires a fresh local database named quota_acceptance_*; never targets app databases. */
class TenantQuotaPostgresAcceptanceTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private TenantQuotaGuard guard;

    @BeforeEach
    void setupIsolatedDatabase() throws Exception {
        String url = System.getenv("QUOTA_ACCEPTANCE_JDBC_URL");
        Assumptions.assumeTrue(url != null && url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/quota_acceptance_[A-Za-z0-9_-]+"),
                "Set QUOTA_ACCEPTANCE_JDBC_URL to a loopback-only disposable quota_acceptance_* database");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url,
                System.getenv().getOrDefault("QUOTA_ACCEPTANCE_DB_USER", "postgres"),
                System.getenv().getOrDefault("QUOTA_ACCEPTANCE_DB_PASSWORD", ""));
        try (Connection connection = dataSource.getConnection()) {
            assertTrue(connection.getMetaData().getURL().contains("quota_acceptance_"));
        }
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        guard = new TenantQuotaGuard(jdbc, "FREE");
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS identity");
        jdbc.execute("CREATE TABLE IF NOT EXISTS identity.tenants (id UUID PRIMARY KEY, status TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS identity.quota_plans (plan_code TEXT PRIMARY KEY, max_cameras INT NOT NULL, max_active_broadcasts INT NOT NULL, enabled BOOLEAN NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS identity.tenant_quota_assignments (tenant_id UUID PRIMARY KEY, plan_code TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS identity.tenant_quota_overrides (tenant_id UUID PRIMARY KEY, max_cameras INT, max_active_broadcasts INT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS cameras (id UUID PRIMARY KEY, tenant_id UUID NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS broadcasts (id UUID PRIMARY KEY, tenant_id UUID NOT NULL, enabled BOOLEAN NOT NULL)");
        jdbc.update("INSERT INTO identity.quota_plans VALUES ('FREE', 3, 1, TRUE) ON CONFLICT (plan_code) DO UPDATE SET max_cameras=3, max_active_broadcasts=1, enabled=TRUE");
    }

    @Test
    void concurrentCameraCreatesSerializeAtOneRemainingSlot() throws Exception {
        UUID tenant = tenant();
        jdbc.update("INSERT INTO cameras VALUES (?, ?), (?, ?)", UUID.randomUUID(), tenant, UUID.randomUUID(), tenant);

        AtomicInteger admitted = compete(tenant, TenantQuotaGuard.Resource.CAMERA,
                () -> jdbc.update("INSERT INTO cameras VALUES (?, ?)", UUID.randomUUID(), tenant));

        assertEquals(1, admitted.get());
        assertEquals(3L, jdbc.queryForObject("SELECT count(*) FROM cameras WHERE tenant_id = ?", Long.class, tenant));
    }

    @Test
    void concurrentBroadcastActivationsSerializeAtOneRemainingSlot() throws Exception {
        UUID tenant = tenant();
        jdbc.update("INSERT INTO broadcasts VALUES (?, ?, FALSE), (?, ?, FALSE)",
                UUID.randomUUID(), tenant, UUID.randomUUID(), tenant);

        AtomicInteger admitted = compete(tenant, TenantQuotaGuard.Resource.BROADCAST,
                () -> jdbc.update("INSERT INTO broadcasts VALUES (?, ?, TRUE)", UUID.randomUUID(), tenant));

        assertEquals(1, admitted.get());
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM broadcasts WHERE tenant_id = ? AND enabled", Long.class, tenant));
    }

    @Test
    void rollbackDoesNotConsumeCameraCapacityAndDeletionReleasesIt() {
        UUID tenant = tenant();
        jdbc.update("INSERT INTO cameras VALUES (?, ?), (?, ?)", UUID.randomUUID(), tenant, UUID.randomUUID(), tenant);
        UUID rolledBackCamera = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> transactions.execute(status -> {
            guard.reserve(tenant, TenantQuotaGuard.Resource.CAMERA);
            jdbc.update("INSERT INTO cameras VALUES (?, ?)", rolledBackCamera, tenant);
            throw new IllegalStateException("synthetic rollback");
        }));
        assertEquals(2L, jdbc.queryForObject("SELECT count(*) FROM cameras WHERE tenant_id = ?", Long.class, tenant));
        transactions.executeWithoutResult(status -> {
            guard.reserve(tenant, TenantQuotaGuard.Resource.CAMERA);
            jdbc.update("INSERT INTO cameras VALUES (?, ?)", UUID.randomUUID(), tenant);
        });
        transactions.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM cameras WHERE id = (SELECT id FROM cameras WHERE tenant_id = ? LIMIT 1)", tenant);
            guard.reserve(tenant, TenantQuotaGuard.Resource.CAMERA);
        });
    }

    @Test
    void loweringQuotaBelowUsagePreservesRowsAndRejectsOnlyFurtherCreation() {
        UUID tenant = tenant();
        jdbc.update("INSERT INTO cameras VALUES (?, ?), (?, ?)", UUID.randomUUID(), tenant, UUID.randomUUID(), tenant);
        jdbc.update("INSERT INTO identity.tenant_quota_overrides (tenant_id, max_cameras) VALUES (?, 1)", tenant);

        TenantQuotaGuard.Usage usage = guard.usage(tenant, TenantQuotaGuard.Resource.CAMERA);

        assertEquals(2, usage.used());
        assertEquals(1, usage.limit());
        assertEquals(0, usage.remaining());
        assertThrows(TenantQuotaGuard.QuotaExceededException.class,
                () -> transactions.executeWithoutResult(status -> guard.reserve(tenant, TenantQuotaGuard.Resource.CAMERA)));
        assertEquals(2L, jdbc.queryForObject("SELECT count(*) FROM cameras WHERE tenant_id = ?", Long.class, tenant));
    }

    @Test
    void disablingBroadcastReleasesItsActiveSlot() {
        UUID tenant = tenant(), broadcast = UUID.randomUUID();
        jdbc.update("INSERT INTO broadcasts VALUES (?, ?, TRUE)", broadcast, tenant);
        assertThrows(TenantQuotaGuard.QuotaExceededException.class,
                () -> transactions.executeWithoutResult(status -> guard.reserve(tenant, TenantQuotaGuard.Resource.BROADCAST)));

        transactions.executeWithoutResult(status -> {
            guard.lock(tenant);
            jdbc.update("UPDATE broadcasts SET enabled = FALSE WHERE id = ? AND tenant_id = ?", broadcast, tenant);
        });
        transactions.executeWithoutResult(status -> {
            guard.reserve(tenant, TenantQuotaGuard.Resource.BROADCAST);
            jdbc.update("UPDATE broadcasts SET enabled = TRUE WHERE id = ? AND tenant_id = ?", broadcast, tenant);
        });
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM broadcasts WHERE tenant_id = ? AND enabled", Long.class, tenant));
    }

    @Test
    void concurrentCameraCreateAndDeleteNeverExceedLimit() throws Exception {
        UUID tenant = tenant();
        jdbc.update("INSERT INTO identity.tenant_quota_overrides (tenant_id, max_cameras) VALUES (?, 1)", tenant);
        UUID existing = UUID.randomUUID();
        jdbc.update("INSERT INTO cameras VALUES (?, ?)", existing, tenant);

        race(
                () -> transactions.executeWithoutResult(status -> {
                    guard.reserve(tenant, TenantQuotaGuard.Resource.CAMERA);
                    jdbc.update("INSERT INTO cameras VALUES (?, ?)", UUID.randomUUID(), tenant);
                }),
                () -> transactions.executeWithoutResult(status -> {
                    guard.lock(tenant);
                    jdbc.update("DELETE FROM cameras WHERE id = ? AND tenant_id = ?", existing, tenant);
                }));

        assertTrue(jdbc.queryForObject("SELECT count(*) FROM cameras WHERE tenant_id = ?", Long.class, tenant) <= 1);
    }

    @Test
    void concurrentBroadcastEnableAndDisableNeverExceedLimit() throws Exception {
        UUID tenant = tenant(), active = UUID.randomUUID(), candidate = UUID.randomUUID();
        jdbc.update("INSERT INTO broadcasts VALUES (?, ?, TRUE), (?, ?, FALSE)", active, tenant, candidate, tenant);

        race(
                () -> transactions.executeWithoutResult(status -> {
                    guard.reserve(tenant, TenantQuotaGuard.Resource.BROADCAST);
                    jdbc.update("UPDATE broadcasts SET enabled=TRUE WHERE id=? AND tenant_id=?", candidate, tenant);
                }),
                () -> transactions.executeWithoutResult(status -> {
                    guard.lock(tenant);
                    jdbc.update("UPDATE broadcasts SET enabled=FALSE WHERE id=? AND tenant_id=?", active, tenant);
                }));

        assertTrue(jdbc.queryForObject("SELECT count(*) FROM broadcasts WHERE tenant_id = ? AND enabled", Long.class, tenant) <= 1);
    }

    private UUID tenant() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO identity.tenants VALUES (?, 'ACTIVE')", id);
        return id;
    }

    private AtomicInteger compete(UUID tenant, TenantQuotaGuard.Resource resource, Runnable insert) throws Exception {
        AtomicInteger admitted = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) futures.add(executor.submit(() -> {
                ready.countDown();
                try {
                    if (!start.await(3, TimeUnit.SECONDS)) return;
                    transactions.executeWithoutResult(status -> {
                        guard.reserve(tenant, resource);
                        insert.run();
                        admitted.incrementAndGet();
                    });
                } catch (TenantQuotaGuard.QuotaExceededException expected) {
                    // Expected for the operation which obtains the lock after the final slot is used.
                } catch (Exception unexpected) {
                    throw new RuntimeException(unexpected);
                }
            }));
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            start.countDown();
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
        }
        return admitted;
    }

    private void race(Runnable first, Runnable second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var left = executor.submit(() -> runTransition(first, ready, start));
            var right = executor.submit(() -> runTransition(second, ready, start));
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            start.countDown();
            left.get(5, TimeUnit.SECONDS);
            right.get(5, TimeUnit.SECONDS);
        }
    }

    private void runTransition(Runnable operation, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("race start timed out");
            operation.run();
        } catch (TenantQuotaGuard.QuotaExceededException expected) {
            // A create/enable that wins the lock before release is correctly rejected at capacity.
        } catch (Exception unexpected) {
            throw new RuntimeException(unexpected);
        }
    }
}
