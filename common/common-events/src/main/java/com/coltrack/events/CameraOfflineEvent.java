package com.coltrack.events;

import java.time.Instant;
import java.util.UUID;


public record CameraOfflineEvent(

        UUID cameraId,

        Instant detectedAt,

        String reason

) {}
