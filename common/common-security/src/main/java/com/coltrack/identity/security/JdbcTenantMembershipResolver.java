package com.coltrack.identity.security;

import com.coltrack.identity.model.Role;
import com.coltrack.identity.model.TenantMembership;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Reads application-owned membership. IdP groups and request tenant selectors are never authoritative. */
public final class JdbcTenantMembershipResolver implements MembershipResolver, PermissionEvaluator {
    private static final String SELECT_MEMBERSHIP = """
            SELECT tenant_id, user_id, tenant_slug, tenant_name, role_code
            FROM identity.resolve_tenant_membership(?, ?, ?)
            """;

    private final JdbcTemplate jdbc;

    public JdbcTenantMembershipResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<TenantMembership> findMembership(AuthenticatedPrincipal principal, UUID tenantId) {
        List<TenantMembership> rows = jdbc.query(SELECT_MEMBERSHIP,
                (rs, row) -> new TenantMembership(rs.getObject("tenant_id", UUID.class),
                        rs.getObject("user_id", UUID.class), rs.getString("tenant_slug"),
                        rs.getString("tenant_name"), Role.valueOf(rs.getString("role_code"))),
                principal.issuer(), principal.subject(), tenantId);
        return rows.stream().findFirst();
    }

    public boolean hasPermission(UUID userId, UUID tenantId, String permission) {
        List<Role> roles = jdbc.query("""
                SELECT role_code FROM identity.resolve_active_role(?, ?)
                """, (rs, row) -> Role.valueOf(rs.getString(1)), userId, tenantId);
        return roles.stream().anyMatch(role -> TenantPermissions.allows(role, permission));
    }

    @Override
    public boolean hasPermission(AuthenticatedPrincipal principal, UUID tenantId, String permission) {
        return findMembership(principal, tenantId)
                .map(membership -> TenantPermissions.allows(membership.role(), permission))
                .orElse(false);
    }
}
