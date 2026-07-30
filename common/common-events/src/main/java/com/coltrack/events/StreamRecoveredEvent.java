package com.coltrack.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when stream recovered after failure.
 */
public record StreamRecoveredEvent(

        UUID eventId,

        UUID cameraId,

        Instant recoveredAt

) {
}
