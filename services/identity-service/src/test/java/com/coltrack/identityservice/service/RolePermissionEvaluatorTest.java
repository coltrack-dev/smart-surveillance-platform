package com.coltrack.identityservice.service;

import com.coltrack.identity.model.Role;
import com.coltrack.identity.model.TenantMembership;
import com.coltrack.identity.security.AuthenticatedPrincipal;
import com.coltrack.identity.security.MembershipResolver;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RolePermissionEvaluatorTest {
    @Test
    void deniesPermissionWhenTenantMembershipDoesNotExist() {
        MembershipResolver resolver = (principal, tenantId) -> Optional.empty();
        RolePermissionEvaluator permissions = new RolePermissionEvaluator(resolver);

        assertFalse(permissions.hasPermission(
                new AuthenticatedPrincipal("https://issuer.example", "user", null, false, null),
                UUID.randomUUID(), "camera.read"));
    }

    @Test
    void viewerCanViewPlaybackButCannotManageBroadcasts() {
        UUID tenantId = UUID.randomUUID();
        MembershipResolver resolver = (principal, selectedTenant) -> Optional.of(
                new TenantMembership(tenantId, UUID.randomUUID(), "tenant", "Tenant", Role.VIEWER));
        RolePermissionEvaluator permissions = new RolePermissionEvaluator(resolver);
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
                "https://issuer.example", "user", null, false, null);

        assertTrue(permissions.hasPermission(principal, tenantId, "playback.view"));
        assertFalse(permissions.hasPermission(principal, tenantId, "broadcast.manage"));
    }
}
