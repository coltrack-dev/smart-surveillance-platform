package com.coltrack.identity.model;

import java.time.Instant;
import java.util.UUID;

public record User(UUID id, String issuer, String subject, String email, boolean emailVerified,
                   String displayName, boolean enabled, Instant createdAt, Instant updatedAt) {}
