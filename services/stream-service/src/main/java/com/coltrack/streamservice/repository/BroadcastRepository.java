package com.coltrack.streamservice.repository;

import com.coltrack.streamservice.model.Broadcast;
import com.coltrack.streamservice.model.BroadcastVisibility;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class BroadcastRepository {
    private final JdbcTemplate jdbc;

    public BroadcastRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Broadcast> ROW_MAPPER = BroadcastRepository::map;

    public Optional<Broadcast> find(UUID id) {
        return jdbc.query("SELECT * FROM broadcasts WHERE id = ?", ROW_MAPPER, id).stream().findFirst();
    }

    public Optional<Broadcast> find(UUID id, UUID tenantId) {
        return jdbc.query("SELECT * FROM broadcasts WHERE id = ? AND tenant_id = ?", ROW_MAPPER, id, tenantId).stream().findFirst();
    }

    public Optional<Broadcast> findByCamera(UUID cameraId) {
        return jdbc.query("SELECT * FROM broadcasts WHERE camera_id = ?", ROW_MAPPER, cameraId).stream().findFirst();
    }

    public Optional<Broadcast> findByCamera(UUID cameraId, UUID tenantId) {
        return jdbc.query("SELECT * FROM broadcasts WHERE camera_id = ? AND tenant_id = ?", ROW_MAPPER, cameraId, tenantId).stream().findFirst();
    }

    public Optional<Broadcast> findByPublicId(UUID publicId) {
        return jdbc.query("SELECT * FROM broadcasts WHERE public_id = ?", ROW_MAPPER, publicId).stream().findFirst();
    }

    public List<Broadcast> findAll() {
        return jdbc.query("SELECT * FROM broadcasts ORDER BY created_at DESC", ROW_MAPPER);
    }

    public List<Broadcast> findAll(UUID tenantId) {
        return jdbc.query("SELECT * FROM broadcasts WHERE tenant_id = ? ORDER BY created_at DESC", ROW_MAPPER, tenantId);
    }

    public Broadcast create(UUID id, UUID cameraId, UUID publicId, BroadcastVisibility visibility, Instant now) {
        return create(id, cameraId, publicId, visibility, null, now);
    }

    public Broadcast create(UUID id, UUID cameraId, UUID publicId, BroadcastVisibility visibility, UUID tenantId, Instant now) {
        jdbc.update("INSERT INTO broadcasts (id, camera_id, public_id, visibility, enabled, owns_stream, playback_version, tenant_id, created_at, updated_at) VALUES (?, ?, ?, ?, FALSE, FALSE, 0, ?, ?, ?)", id, cameraId, publicId, visibility.name(), tenantId, Timestamp.from(now), Timestamp.from(now));
        return find(id).orElseThrow();
    }

    public Broadcast setEnabled(UUID id, boolean enabled, Instant now) {
        jdbc.update("UPDATE broadcasts SET playback_version = playback_version + CASE WHEN broadcasts.enabled <> ? THEN 1 ELSE 0 END, enabled = ?, updated_at = ? WHERE id = ?", enabled, enabled, Timestamp.from(now), id);
        return find(id).orElseThrow();
    }

    public Broadcast setEnabled(UUID id, UUID tenantId, boolean enabled, Instant now) {
        jdbc.update("UPDATE broadcasts SET playback_version = playback_version + CASE WHEN broadcasts.enabled <> ? THEN 1 ELSE 0 END, enabled = ?, updated_at = ? WHERE id = ? AND tenant_id = ?",
                enabled, enabled, Timestamp.from(now), id, tenantId);
        return find(id, tenantId).orElseThrow();
    }

    public Broadcast setVisibility(UUID id, BroadcastVisibility visibility, Instant now) {
        jdbc.update("UPDATE broadcasts SET playback_version = playback_version + CASE WHEN broadcasts.visibility <> ? THEN 1 ELSE 0 END, visibility = ?, updated_at = ? WHERE id = ?", visibility.name(), visibility.name(), Timestamp.from(now), id);
        return find(id).orElseThrow();
    }

    public Broadcast setVisibility(UUID id, UUID tenantId, BroadcastVisibility visibility, Instant now) {
        jdbc.update("UPDATE broadcasts SET playback_version = playback_version + CASE WHEN broadcasts.visibility <> ? THEN 1 ELSE 0 END, visibility = ?, updated_at = ? WHERE id = ? AND tenant_id = ?",
                visibility.name(), visibility.name(), Timestamp.from(now), id, tenantId);
        return find(id, tenantId).orElseThrow();
    }

    public void delete(UUID id) {
        jdbc.update("DELETE FROM broadcasts WHERE id = ?", id);
    }

    public void delete(UUID id, UUID tenantId) {
        jdbc.update("DELETE FROM broadcasts WHERE id = ? AND tenant_id = ?", id, tenantId);
    }

    public void revokeViewerSessions(UUID broadcastId, Instant now) {
        jdbc.update("UPDATE viewer_sessions SET revoked_at = ? WHERE broadcast_id = ? AND revoked_at IS NULL",
                Timestamp.from(now), broadcastId);
    }

    private static Broadcast map(ResultSet rs, int row) throws SQLException {
        return new Broadcast(
                rs.getObject("id", UUID.class),
                rs.getObject("camera_id", UUID.class),
                rs.getObject("public_id", UUID.class),
                BroadcastVisibility.valueOf(rs.getString("visibility")),
                rs.getBoolean("enabled"),
                rs.getLong("playback_version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getObject("tenant_id", UUID.class)
        );
    }
}
