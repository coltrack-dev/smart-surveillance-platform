package com.coltrack.streamservice.model;

import java.time.Instant;
import java.util.UUID;

public record Broadcast(
        UUID id,
        UUID cameraId,
        boolean enabled,
        boolean ownsStream,
        long playbackVersion,
        Instant createdAt,
        Instant updatedAt
) {}
