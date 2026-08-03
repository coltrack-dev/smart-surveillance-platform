package com.coltrack.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when video streaming has successfully started.
 */
public record StreamStartedEvent(
        UUID eventId,
        UUID cameraId,
        String hlsUrl,
        Instant startedAt
) {
}
