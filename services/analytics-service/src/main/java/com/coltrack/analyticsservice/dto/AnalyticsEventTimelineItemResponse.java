package com.coltrack.analyticsservice.dto;

import com.coltrack.analyticsservice.entity.AnalyticsEventEntity;

import java.math.BigDecimal;
import java.util.UUID;

public record AnalyticsEventTimelineItemResponse(
        UUID eventId,
        BigDecimal videoTimeSeconds,
        String eventType,
        String objectType
) {
    public static AnalyticsEventTimelineItemResponse fromEntity(
            AnalyticsEventEntity entity
    ) {
        return new AnalyticsEventTimelineItemResponse(
                entity.getEventId(),
                entity.getVideoTimeSeconds(),
                entity.getEventType(),
                entity.getObjectType()
        );
    }
}
