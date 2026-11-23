package com.coltrack.identity.security;

import com.coltrack.identity.model.Role;
import java.util.UUID;

public record TenantContext(UUID tenantId, UUID userId, Role role) {}
