package com.coltrack.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Published before stream reconnect attempt.
 */
public record StreamReconnectingEvent(

        UUID eventId,

        UUID cameraId,

        Instant reconnectingAt

) {
}
