package com.coltrack.identity.security;

import com.coltrack.identity.model.TenantMembership;
import java.util.UUID;

public final class TenantAuthorization {
    private final MembershipResolver memberships;
    private final PermissionEvaluator permissions;

    public TenantAuthorization(MembershipResolver memberships, PermissionEvaluator permissions) {
        this.memberships = memberships;
        this.permissions = permissions;
    }

    public TenantContext require(AuthenticatedPrincipal principal, UUID selectedTenant, String permission) {
        TenantMembership membership = memberships.findMembership(principal, selectedTenant)
                .orElseThrow(TenantMembershipRequiredException::new);
        if (!permissions.hasPermission(principal, selectedTenant, permission)) {
            throw new TenantPermissionDeniedException();
        }
        return new TenantContext(membership.tenantId(), membership.userId(), membership.role());
    }

    public static class TenantMembershipRequiredException extends RuntimeException {}
    public static class TenantPermissionDeniedException extends RuntimeException {}
}
