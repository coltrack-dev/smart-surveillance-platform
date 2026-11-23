package com.coltrack.identity.security;

import com.coltrack.identity.model.TenantMembership;
import java.util.Optional;
import java.util.UUID;

public interface MembershipResolver {
    Optional<TenantMembership> findMembership(AuthenticatedPrincipal principal, UUID tenantId);

    default Optional<TenantContext> resolve(AuthenticatedPrincipal principal, UUID tenantId) {
        return findMembership(principal, tenantId)
                .map(membership -> new TenantContext(membership.tenantId(), membership.userId(), membership.role()));
    }
}
