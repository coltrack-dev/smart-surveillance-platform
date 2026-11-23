package com.coltrack.websocketservice.security;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Optional;
import java.util.UUID;

/** Resolves event ownership from the persisted Camera row; Kafka payload tenant claims are ignored. */
public class CameraTenantLookup {
    private final JdbcTemplate jdbc;

    public CameraTenantLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UUID> findAssignedTenant(UUID cameraId) {
        return jdbc.query("SELECT tenant_id FROM cameras WHERE id = ? AND tenant_id IS NOT NULL",
                rs -> rs.next() ? Optional.of(rs.getObject(1, UUID.class)) : Optional.empty(), cameraId);
    }
}
