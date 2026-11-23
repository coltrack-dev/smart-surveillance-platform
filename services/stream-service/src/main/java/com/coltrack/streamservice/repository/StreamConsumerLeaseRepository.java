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
        acquire(cameraId, null, consumerId, consumerType);
    }

    public void acquire(UUID cameraId, UUID tenantId, String consumerId, String consumerType) {
        jdbc.update("INSERT INTO stream_consumer_leases (camera_id, consumer_id, consumer_type, tenant_id, created_at) VALUES (?, ?, ?, ?, ?) " +
                        "ON CONFLICT (camera_id, consumer_id) DO UPDATE SET tenant_id = COALESCE(stream_consumer_leases.tenant_id, EXCLUDED.tenant_id)",
                cameraId, consumerId, consumerType, tenantId, Timestamp.from(Instant.now()));
    }

    public void release(UUID cameraId, String consumerId) {
        jdbc.update("DELETE FROM stream_consumer_leases WHERE camera_id = ? AND consumer_id = ?", cameraId, consumerId);
    }

    public void release(UUID cameraId, UUID tenantId, String consumerId) {
        jdbc.update("DELETE FROM stream_consumer_leases WHERE camera_id = ? AND tenant_id = ? AND consumer_id = ?",
                cameraId, tenantId, consumerId);
    }

    public void releaseAll(UUID cameraId) {
        jdbc.update("DELETE FROM stream_consumer_leases WHERE camera_id = ?", cameraId);
    }

    public void releaseAll(UUID cameraId, UUID tenantId) {
        jdbc.update("DELETE FROM stream_consumer_leases WHERE camera_id = ? AND tenant_id = ?", cameraId, tenantId);
    }

    public void releaseLegacyLeases() {
        jdbc.update("DELETE FROM stream_consumer_leases WHERE consumer_type = 'LEGACY'");
    }

    public boolean hasConsumers(UUID cameraId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM stream_consumer_leases WHERE camera_id = ?", Integer.class, cameraId);
        return count != null && count > 0;
    }

    public List<StreamConsumerLease> findAll() {
        return jdbc.query("SELECT camera_id, consumer_id, consumer_type, tenant_id FROM stream_consumer_leases ORDER BY created_at",
                (rs, row) -> new StreamConsumerLease(rs.getObject("camera_id", UUID.class), rs.getString("consumer_id"),
                        rs.getString("consumer_type"), rs.getObject("tenant_id", UUID.class)));
    }

    public List<StreamConsumerLease> findAll(UUID tenantId) {
        return jdbc.query("SELECT camera_id, consumer_id, consumer_type, tenant_id FROM stream_consumer_leases WHERE tenant_id = ? ORDER BY created_at",
                (rs, row) -> new StreamConsumerLease(rs.getObject("camera_id", UUID.class), rs.getString("consumer_id"),
                        rs.getString("consumer_type"), rs.getObject("tenant_id", UUID.class)), tenantId);
    }

    public boolean hasConsumers(UUID cameraId, UUID tenantId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM stream_consumer_leases WHERE camera_id = ? AND tenant_id = ?",
                Integer.class, cameraId, tenantId);
        return count != null && count > 0;
    }

    /** Rebuilds Broadcast leases from desired state and removes disable/delete orphans. */
    public void reconcileBroadcastLeases() {
        jdbc.update("INSERT INTO stream_consumer_leases (camera_id, consumer_id, consumer_type, tenant_id, created_at) " +
                        "SELECT camera_id, 'broadcast:' || id::text, 'BROADCAST', tenant_id, updated_at FROM broadcasts WHERE enabled = TRUE " +
                        "ON CONFLICT (camera_id, consumer_id) DO UPDATE SET tenant_id = COALESCE(stream_consumer_leases.tenant_id, EXCLUDED.tenant_id)");
        jdbc.update("DELETE FROM stream_consumer_leases l WHERE l.consumer_type = 'BROADCAST' AND NOT EXISTS " +
                "(SELECT 1 FROM broadcasts b WHERE b.enabled = TRUE AND b.camera_id = l.camera_id AND l.consumer_id = 'broadcast:' || b.id::text)");
    }
}
