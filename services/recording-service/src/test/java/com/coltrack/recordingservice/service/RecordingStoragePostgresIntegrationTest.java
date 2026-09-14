package com.coltrack.recordingservice.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

@Testcontainers(disabledWithoutDocker = true)
class RecordingStoragePostgresIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void migrationCreatesStorageSchemaAndRejectsDuplicateActiveS3Keys() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        UUID recordingId = UUID.randomUUID();
        jdbc.update("insert into recording_sessions(id,camera_id,file_path) values (?,?,?)",
                recordingId, UUID.randomUUID(), "/tmp/recording");
        jdbc.update("insert into recording_objects(id,recording_id,s3_key,cleanup_status) values (?,?,?,'AVAILABLE')",
                UUID.randomUUID(), recordingId, "recordings/test.mkv");

        assertThrows(DuplicateKeyException.class, () -> jdbc.update(
                "insert into recording_objects(id,recording_id,s3_key,cleanup_status) values (?,?,?,'AVAILABLE')",
                UUID.randomUUID(), recordingId, "recordings/test.mkv"));

        CleanupHistoryService history = new CleanupHistoryService(jdbc);
        history.track("S3", "MANUAL", () -> "done",
                ignored -> new CleanupHistoryService.Metrics(1, 1, 0, 42),
                ignored -> List.of(new CleanupHistoryService.Item(
                        recordingId, "recordings/test.mkv", "DELETED", 42, null)));

        assertEquals(1L, jdbc.queryForObject("select count(*) from storage_cleanup_runs", Long.class));
        assertEquals(1L, jdbc.queryForObject("select count(*) from storage_cleanup_run_items", Long.class));

        RecordingUsageGuard usageGuard = new RecordingUsageGuard(jdbc);
        try (RecordingUsageGuard.Lease ignored = usageGuard.acquire(
                recordingId, "PLAYBACK", java.time.Duration.ofMinutes(1))) {
            assertTrue(usageGuard.isInUse(recordingId));
        }
        assertFalse(usageGuard.isInUse(recordingId));
    }

    @Test
    void advisoryLockRejectsConcurrentCleanupAcrossConnections() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        DistributedLockService first = new DistributedLockService(new JdbcTemplate(dataSource));
        DistributedLockService second = new DistributedLockService(new JdbcTemplate(dataSource));
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var running = executor.submit(() -> first.execute(
                    DistributedLockService.S3_CLEANUP, "cleanup", () -> {
                        acquired.countDown();
                        try {
                            release.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                        }
                        return true;
                    }));
            acquired.await(10, TimeUnit.SECONDS);
            assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> second.execute(DistributedLockService.S3_CLEANUP, "cleanup", () -> true));
            release.countDown();
            running.get(10, TimeUnit.SECONDS);
        }
    }
}
