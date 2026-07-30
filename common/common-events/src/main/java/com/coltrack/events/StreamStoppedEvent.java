package com.coltrack.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when video streaming has stopped.
 */
public record StreamStoppedEvent(

        UUID eventId,

        UUID cameraId,

        Instant stoppedAt

) {
}
