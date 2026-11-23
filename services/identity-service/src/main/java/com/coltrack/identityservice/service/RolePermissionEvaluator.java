package com.coltrack.identityservice.service;

import com.coltrack.identity.security.AuthenticatedPrincipal;
import com.coltrack.identity.security.MembershipResolver;
import com.coltrack.identity.security.PermissionEvaluator;
import com.coltrack.identity.security.TenantPermissions;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class RolePermissionEvaluator implements PermissionEvaluator {
    private final MembershipResolver memberships;

    public RolePermissionEvaluator(MembershipResolver memberships) {
        this.memberships = memberships;
    }

    @Override
    public boolean hasPermission(AuthenticatedPrincipal principal, UUID tenantId, String permission) {
        return memberships.findMembership(principal, tenantId)
                .map(membership -> TenantPermissions.allows(membership.role(), permission))
                .orElse(false);
    }
}
