package com.coltrack.streamservice.repository;

import com.coltrack.streamservice.model.Broadcast;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
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

    public Optional<Broadcast> findByCamera(UUID cameraId) {
        return jdbc.query("SELECT * FROM broadcasts WHERE camera_id = ?", ROW_MAPPER, cameraId).stream().findFirst();
    }

    public List<Broadcast> findAll() {
        return jdbc.query("SELECT * FROM broadcasts ORDER BY created_at DESC", ROW_MAPPER);
    }

    public Broadcast create(UUID id, UUID cameraId, Instant now) {
        jdbc.update("INSERT INTO broadcasts (id, camera_id, enabled, owns_stream, playback_version, created_at, updated_at) VALUES (?, ?, FALSE, FALSE, 0, ?, ?)", id, cameraId, now, now);
        return find(id).orElseThrow();
    }

    public Broadcast setState(UUID id, boolean enabled, boolean ownsStream, Instant now) {
        jdbc.update("UPDATE broadcasts SET playback_version = playback_version + CASE WHEN enabled <> ? THEN 1 ELSE 0 END, enabled = ?, owns_stream = ?, updated_at = ? WHERE id = ?", enabled, enabled, ownsStream, now, id);
        return find(id).orElseThrow();
    }

    public void delete(UUID id) {
        jdbc.update("DELETE FROM broadcasts WHERE id = ?", id);
    }

    private static Broadcast map(ResultSet rs, int row) throws SQLException {
        return new Broadcast(
                rs.getObject("id", UUID.class),
                rs.getObject("camera_id", UUID.class),
                rs.getBoolean("enabled"),
                rs.getBoolean("owns_stream"),
                rs.getLong("playback_version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }
}
