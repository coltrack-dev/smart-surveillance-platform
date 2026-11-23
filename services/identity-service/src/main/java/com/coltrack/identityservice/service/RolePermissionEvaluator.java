package com.coltrack.identityservice.service;

import com.coltrack.identity.model.Role;
import com.coltrack.identity.security.AuthenticatedPrincipal;
import com.coltrack.identity.security.MembershipResolver;
import com.coltrack.identity.security.PermissionEvaluator;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class RolePermissionEvaluator implements PermissionEvaluator {
    private static final Map<Role, Set<String>> PERMISSIONS = Map.of(
            Role.OWNER, Set.of("identity.read", "tenant.manage", "camera.read", "camera.manage",
                    "stream.manage", "broadcast.manage", "recording.read", "analytics.read"),
            Role.ADMIN, Set.of("identity.read", "camera.read", "camera.manage", "stream.manage",
                    "broadcast.manage", "recording.read", "analytics.read"),
            Role.OPERATOR, Set.of("camera.read", "camera.manage", "stream.manage", "broadcast.manage"),
            Role.VIEWER, Set.of("camera.read", "recording.read", "analytics.read", "playback.view"));
    private final MembershipResolver memberships;

    public RolePermissionEvaluator(MembershipResolver memberships) {
        this.memberships = memberships;
    }

    @Override
    public boolean hasPermission(AuthenticatedPrincipal principal, UUID tenantId, String permission) {
        return memberships.findMembership(principal, tenantId)
                .map(membership -> PERMISSIONS.get(membership.role()).contains(permission))
                .orElse(false);
    }
}
