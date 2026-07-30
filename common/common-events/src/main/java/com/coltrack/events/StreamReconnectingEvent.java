package com.coltrack.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Published before reconnecting to the RTSP source.
 */
public record StreamReconnectingEvent(

        UUID cameraId,

        int reconnectAttempt,

        Instant reconnectAt

) {
}
