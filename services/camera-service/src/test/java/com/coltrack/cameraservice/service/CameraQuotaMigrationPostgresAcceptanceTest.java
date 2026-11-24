package com.coltrack.cameraservice.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in additive migration test against a disposable loopback PostgreSQL database. */
class CameraQuotaMigrationPostgresAcceptanceTest {
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void requireDisposableDatabase() {
        String url = System.getenv("QUOTA_ACCEPTANCE_JDBC_URL");
        Assumptions.assumeTrue(url != null && url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/quota_acceptance_[A-Za-z0-9_-]+"),
                "Set QUOTA_ACCEPTANCE_JDBC_URL to a loopback-only disposable quota_acceptance_* database");
        dataSource = new DriverManagerDataSource(url,
                System.getenv().getOrDefault("QUOTA_ACCEPTANCE_DB_USER", "postgres"),
                System.getenv().getOrDefault("QUOTA_ACCEPTANCE_DB_PASSWORD", ""));
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void cameraV1ThroughV4ApplyAdditivelyAndPreserveLegacyCameraRow() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS cameras (id UUID PRIMARY KEY, camera_number INTEGER NOT NULL UNIQUE, name TEXT NOT NULL, rtsp_url TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), status TEXT NOT NULL DEFAULT 'OFFLINE')");
        jdbc.update("INSERT INTO cameras (id, camera_number, name, rtsp_url, status) VALUES ('00000000-0000-0000-0000-000000000101', 901, 'synthetic legacy', 'rtsp://synthetic.invalid/live', 'OFFLINE') ON CONFLICT (id) DO NOTHING");

        Flyway flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .table("flyway_camera_schema_history").baselineOnMigrate(true).baselineVersion("0").load();
        flyway.migrate();

        List<String> versions = jdbc.queryForList("SELECT version FROM flyway_camera_schema_history WHERE success ORDER BY installed_rank", String.class);
        assertEquals(List.of("0", "1", "2", "3", "4"), versions);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM cameras WHERE id='00000000-0000-0000-0000-000000000101' AND camera_number=901 AND tenant_id IS NULL", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='camera_create_idempotency'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name='camera_create_idempotency' AND column_name='request_fingerprint'", Integer.class));
    }
}
