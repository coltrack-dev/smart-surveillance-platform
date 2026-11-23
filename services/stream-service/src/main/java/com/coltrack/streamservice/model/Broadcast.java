package com.coltrack.streamservice.model;

import java.time.Instant;
import java.util.UUID;

public record Broadcast(
        UUID id,
        UUID cameraId,
        UUID publicId,
        BroadcastVisibility visibility,
        boolean enabled,
        long playbackVersion,
        Instant createdAt,
        Instant updatedAt
) {}
