package com.coltrack.cameraservice.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class CameraCreateIdempotencyRepository {
    private final JdbcTemplate jdbc;

    public CameraCreateIdempotencyRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Replay> find(UUID tenantId, String keyHash) {
        return jdbc.query("SELECT request_fingerprint, camera_id FROM camera_create_idempotency WHERE tenant_id = ? AND idempotency_key_hash = ?",
                rs -> rs.next() ? Optional.of(new Replay(rs.getString(1), rs.getObject(2, UUID.class))) : Optional.empty(), tenantId, keyHash);
    }

    public void save(UUID tenantId, String keyHash, String fingerprint, UUID cameraId) {
        jdbc.update("INSERT INTO camera_create_idempotency (tenant_id, idempotency_key_hash, request_fingerprint, camera_id) VALUES (?, ?, ?, ?)",
                tenantId, keyHash, fingerprint, cameraId);
    }

    public record Replay(String fingerprint, UUID cameraId) {}
}
