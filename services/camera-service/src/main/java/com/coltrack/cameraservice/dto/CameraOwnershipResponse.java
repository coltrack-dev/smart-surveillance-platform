package com.coltrack.cameraservice.dto;

import java.time.Instant;
import java.util.UUID;

public record CameraOwnershipResponse(UUID cameraId, UUID tenantId, String state, Instant changedAt) {}
