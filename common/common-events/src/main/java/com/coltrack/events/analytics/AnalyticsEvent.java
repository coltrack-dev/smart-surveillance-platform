package com.coltrack.events.analytics;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AnalyticsEvent(
        UUID eventId,
        String eventType,
        String cameraId,
        Long trackId,
        String objectType,
        String direction,
        BigDecimal confidence,
        Long frameNumber,
        BigDecimal videoTimeSeconds,
        OffsetDateTime occurredAt
) {
}
