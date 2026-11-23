package com.coltrack.identity.security;

import java.util.UUID;

public interface PermissionEvaluator {
    boolean hasPermission(AuthenticatedPrincipal principal, UUID tenantId, String permission);
}
