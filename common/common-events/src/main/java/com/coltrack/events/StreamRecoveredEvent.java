package com.coltrack.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when stream has been successfully restored.
 */
public record StreamRecoveredEvent(

        UUID cameraId,

        int reconnectCount,

        Instant recoveredAt

) {
}
