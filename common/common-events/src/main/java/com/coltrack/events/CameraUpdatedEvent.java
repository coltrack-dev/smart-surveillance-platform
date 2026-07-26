package com.coltrack.events;

import java.time.Instant;
import java.util.UUID;


public record CameraUpdatedEvent(

        UUID cameraId,

        String name,

        String location,

        String rtspUrl,

        Instant updatedAt

) {
}
