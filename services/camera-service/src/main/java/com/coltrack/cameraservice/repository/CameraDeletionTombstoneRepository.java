package com.coltrack.cameraservice.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class CameraDeletionTombstoneRepository {
    private final JdbcTemplate jdbc;

    public CameraDeletionTombstoneRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(UUID cameraId, UUID tenantId, Instant deletedAt) {
        jdbc.update("INSERT INTO camera_deletion_tombstones(camera_id, tenant_id, deleted_at) VALUES (?, ?, ?) " +
                "ON CONFLICT(camera_id) DO UPDATE SET tenant_id = EXCLUDED.tenant_id, deleted_at = EXCLUDED.deleted_at",
                cameraId, tenantId, java.sql.Timestamp.from(deletedAt));
    }

    public Optional<Tombstone> find(UUID cameraId) {
        return jdbc.query("SELECT camera_id, tenant_id, deleted_at FROM camera_deletion_tombstones WHERE camera_id = ?",
                rs -> rs.next() ? Optional.of(new Tombstone(rs.getObject("camera_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class), rs.getTimestamp("deleted_at").toInstant())) : Optional.empty(),
                cameraId);
    }

    public record Tombstone(UUID cameraId, UUID tenantId, Instant deletedAt) {}
}
