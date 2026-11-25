package com.coltrack.identity.security;

import com.coltrack.identity.model.Role;
import com.coltrack.identity.model.TenantMembership;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JdbcTenantMembershipResolverTest {
    @Test
    void membershipLookupUsesRestrictedIdentityFunction() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
                "https://issuer.example/realm", "subject-1", null, false, null);
        when(jdbc.query(contains("identity.resolve_tenant_membership"), any(RowMapper.class),
                eq(principal.issuer()), eq(principal.subject()), eq(tenantId)))
                .thenReturn(List.of(new TenantMembership(tenantId, userId, "tenant", "Tenant", Role.VIEWER)));

        var membership = new JdbcTenantMembershipResolver(jdbc).findMembership(principal, tenantId).orElseThrow();

        assertEquals(userId, membership.userId());
        assertEquals(Role.VIEWER, membership.role());
        verify(jdbc).query(contains("identity.resolve_tenant_membership"), any(RowMapper.class),
                eq(principal.issuer()), eq(principal.subject()), eq(tenantId));
    }

    @Test
    void permissionLookupUsesRestrictedIdentityFunction() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        when(jdbc.query(contains("identity.resolve_active_role"), any(RowMapper.class), eq(userId), eq(tenantId)))
                .thenReturn(List.of(Role.VIEWER));

        boolean allowed = new JdbcTenantMembershipResolver(jdbc).hasPermission(userId, tenantId, "playback.view");

        assertTrue(allowed);
        verify(jdbc).query(contains("identity.resolve_active_role"), any(RowMapper.class), eq(userId), eq(tenantId));
    }
}
