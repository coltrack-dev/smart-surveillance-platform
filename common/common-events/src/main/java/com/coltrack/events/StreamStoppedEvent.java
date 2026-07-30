package com.coltrack.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when video streaming has been stopped.
 */
public record StreamStoppedEvent(

        UUID cameraId,

        Instant stoppedAt

) {
}
