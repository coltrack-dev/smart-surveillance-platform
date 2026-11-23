package com.coltrack.events;

import java.time.Instant;
import java.util.UUID;


public record CameraUpdatedEvent(

        UUID cameraId,

        String name,

        String location,

        String rtspUrl,

        Instant updatedAt,

        UUID tenantId

) {
    public CameraUpdatedEvent(UUID cameraId, String name, String location, String rtspUrl, Instant updatedAt) {
        this(cameraId, name, location, rtspUrl, updatedAt, null);
    }
}
