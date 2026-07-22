package com.coltrack.events;

import java.time.Instant;
import java.util.UUID;


public record MotionDetectedEvent(

        UUID cameraId,

        Instant detectedAt,

        String zone

) {}
