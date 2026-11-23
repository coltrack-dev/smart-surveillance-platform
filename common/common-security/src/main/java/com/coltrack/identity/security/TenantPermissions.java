package com.coltrack.identity.security;

import com.coltrack.identity.model.Role;
import java.util.Map;
import java.util.Set;

public final class TenantPermissions {
    private static final Map<Role, Set<String>> MATRIX = Map.of(
            Role.OWNER, Set.of("identity.read", "tenant.manage", "camera.read", "camera.manage",
                    "stream.manage", "broadcast.read", "broadcast.manage", "playback.view", "recording.read", "analytics.read"),
            Role.ADMIN, Set.of("identity.read", "camera.read", "camera.manage", "stream.manage",
                    "broadcast.read", "broadcast.manage", "playback.view", "recording.read", "analytics.read"),
            Role.OPERATOR, Set.of("camera.read", "camera.manage", "stream.manage",
                    "broadcast.read", "broadcast.manage", "playback.view"),
            Role.VIEWER, Set.of("camera.read", "broadcast.read", "playback.view", "recording.read", "analytics.read"));

    private TenantPermissions() {}

    public static boolean allows(Role role, String permission) {
        return MATRIX.getOrDefault(role, Set.of()).contains(permission);
    }
}
