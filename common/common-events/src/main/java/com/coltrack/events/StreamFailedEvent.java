package com.coltrack.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when stream processing failed.
 */
public record StreamFailedEvent(

        UUID eventId,

        UUID cameraId,

        String reason,

        Instant failedAt

) {
}
