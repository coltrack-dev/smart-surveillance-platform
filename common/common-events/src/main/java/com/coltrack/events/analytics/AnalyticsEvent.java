package com.coltrack.events.analytics;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record AnalyticsEvent(
        UUID eventId,
        Integer schemaVersion,
        String eventType,
        String cameraId,

        Long trackId,
        String objectType,
        BigDecimal confidence,

        Long frameNumber,
        BigDecimal videoTimeSeconds,
        OffsetDateTime occurredAt,

        Map<String, Object> attributes
) {
}
