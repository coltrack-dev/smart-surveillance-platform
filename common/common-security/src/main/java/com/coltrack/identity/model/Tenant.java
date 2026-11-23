package com.coltrack.identity.model;

import java.util.UUID;

public record Tenant(UUID id, String slug, String displayName, String status) {}
