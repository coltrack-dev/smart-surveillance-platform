package com.coltrack.streamservice.repository;

import com.coltrack.streamservice.model.StreamConsumerLease;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@Repository
public class StreamConsumerLeaseRepository {
    private final JdbcTemplate jdbc;

    public StreamConsumerLeaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void acquire(UUID cameraId, String consumerId, String consumerType) {
        jdbc.update("INSERT INTO stream_consumer_leases (camera_id, consumer_id, consumer_type, created_at) VALUES (?, ?, ?, ?) ON CONFLICT (camera_id, consumer_id) DO NOTHING",
                cameraId, consumerId, consumerType, Timestamp.from(Instant.now()));
    }

    public void release(UUID cameraId, String consumerId) {
        jdbc.update("DELETE FROM stream_consumer_leases WHERE camera_id = ? AND consumer_id = ?", cameraId, consumerId);
    }

    public void releaseAll(UUID cameraId) {
        jdbc.update("DELETE FROM stream_consumer_leases WHERE camera_id = ?", cameraId);
    }

    public void releaseLegacyLeases() {
        jdbc.update("DELETE FROM stream_consumer_leases WHERE consumer_type = 'LEGACY'");
    }

    public boolean hasConsumers(UUID cameraId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM stream_consumer_leases WHERE camera_id = ?", Integer.class, cameraId);
        return count != null && count > 0;
    }

    public List<StreamConsumerLease> findAll() {
        return jdbc.query("SELECT camera_id, consumer_id, consumer_type FROM stream_consumer_leases ORDER BY created_at",
                (rs, row) -> new StreamConsumerLease(rs.getObject("camera_id", UUID.class), rs.getString("consumer_id"), rs.getString("consumer_type")));
    }

    /** Rebuilds Broadcast leases from desired state and removes disable/delete orphans. */
    public void reconcileBroadcastLeases() {
        jdbc.update("INSERT INTO stream_consumer_leases (camera_id, consumer_id, consumer_type, created_at) " +
                        "SELECT camera_id, 'broadcast:' || id::text, 'BROADCAST', updated_at FROM broadcasts WHERE enabled = TRUE " +
                        "ON CONFLICT (camera_id, consumer_id) DO NOTHING");
        jdbc.update("DELETE FROM stream_consumer_leases l WHERE l.consumer_type = 'BROADCAST' AND NOT EXISTS " +
                "(SELECT 1 FROM broadcasts b WHERE b.enabled = TRUE AND b.camera_id = l.camera_id AND l.consumer_id = 'broadcast:' || b.id::text)");
    }
}
