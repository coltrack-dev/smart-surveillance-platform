package com.coltrack.streamservice.client.dto;

import java.util.UUID;

public record CameraOwnershipDto(UUID cameraId, UUID tenantId, String state) {}
