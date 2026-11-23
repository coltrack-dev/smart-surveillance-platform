package com.coltrack.identityservice.repository;

import com.coltrack.identity.model.Role;
import com.coltrack.identity.model.TenantMembership;
import com.coltrack.identity.model.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class IdentityRepository {
    private final JdbcTemplate jdbc;

    public IdentityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public User discover(String issuer, String subject, String email, boolean emailVerified, String displayName) {
        return jdbc.queryForObject("""
                INSERT INTO identity.users (id, issuer, subject, email, email_verified, display_name, enabled)
                VALUES (?, ?, ?, ?, ?, ?, TRUE)
                ON CONFLICT (issuer, subject) DO UPDATE
                    SET email = EXCLUDED.email,
                        email_verified = EXCLUDED.email_verified,
                        display_name = EXCLUDED.display_name,
                        updated_at = now()
                RETURNING id, issuer, subject, email, email_verified, display_name, enabled, created_at, updated_at
                """, (rs, row) -> mapUser(rs), UUID.randomUUID(), issuer, subject,
                email, emailVerified, displayName);
    }

    public Optional<User> findUser(String issuer, String subject) {
        return jdbc.query("""
                SELECT id, issuer, subject, email, email_verified, display_name, enabled, created_at, updated_at
                FROM identity.users WHERE issuer = ? AND subject = ?
                """, (rs, row) -> mapUser(rs), issuer, subject).stream().findFirst();
    }

    public List<TenantMembership> memberships(UUID userId) {
        return jdbc.query("""
                SELECT m.tenant_id, m.user_id, t.slug, t.display_name, m.role_code
                FROM identity.tenant_memberships m
                JOIN identity.tenants t ON t.id = m.tenant_id
                WHERE m.user_id = ? AND m.status = 'ACTIVE' AND t.status = 'ACTIVE'
                ORDER BY t.display_name, t.id
                """, (rs, row) -> mapMembership(rs), userId);
    }

    public Optional<TenantMembership> membership(UUID userId, UUID tenantId) {
        return jdbc.query("""
                SELECT m.tenant_id, m.user_id, t.slug, t.display_name, m.role_code
                FROM identity.tenant_memberships m
                JOIN identity.tenants t ON t.id = m.tenant_id
                WHERE m.user_id = ? AND m.tenant_id = ?
                  AND m.status = 'ACTIVE' AND t.status = 'ACTIVE'
                """, (rs, row) -> mapMembership(rs), userId, tenantId).stream().findFirst();
    }

    private static User mapUser(ResultSet rs) throws SQLException {
        return new User(rs.getObject("id", UUID.class), rs.getString("issuer"),
                rs.getString("subject"), rs.getString("email"), rs.getBoolean("email_verified"),
                rs.getString("display_name"), rs.getBoolean("enabled"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private static TenantMembership mapMembership(ResultSet rs) throws SQLException {
        return new TenantMembership(rs.getObject("tenant_id", UUID.class),
                rs.getObject("user_id", UUID.class), rs.getString("slug"),
                rs.getString("display_name"), Role.valueOf(rs.getString("role_code")));
    }
}
