package com.coltrack.events;

import java.time.Instant;
import java.util.UUID;

public record CameraDeletedEvent(

        UUID cameraId,

        Instant deletedAt,

        UUID tenantId

) {
    public CameraDeletedEvent(UUID cameraId, Instant deletedAt) {
        this(cameraId, deletedAt, null);
    }
}
