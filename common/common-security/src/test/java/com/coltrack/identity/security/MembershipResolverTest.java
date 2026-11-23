package com.coltrack.identity.security;

import com.coltrack.identity.model.Role;
import com.coltrack.identity.model.TenantMembership;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MembershipResolverTest {
    @Test
    void tenantSelectorDoesNotCreateMembership() {
        UUID tenant = UUID.randomUUID();
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
                "https://issuer.example/realms/streaming", "external-subject", null, false, null);
        MembershipResolver resolver = (user, selectedTenant) -> Optional.empty();
        assertTrue(resolver.resolve(principal, tenant).isEmpty());
    }

    @Test
    void resolvedTenantContextComesFromMembership() {
        UUID tenant = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
                "https://issuer.example/realms/streaming", "external-subject", null, false, null);
        MembershipResolver resolver = (user, selectedTenant) -> Optional.of(
                new TenantMembership(tenant, userId, "tenant-a", "Tenant A", Role.VIEWER));
        TenantContext context = resolver.resolve(principal, tenant).orElseThrow();
        assertEquals(tenant, context.tenantId());
        assertEquals(userId, context.userId());
        assertEquals(Role.VIEWER, context.role());
    }
}
