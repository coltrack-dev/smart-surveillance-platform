package com.coltrack.identity.model;

import java.util.UUID;

public record TenantMembership(UUID tenantId, UUID userId, String tenantSlug, String tenantName, Role role) {}
