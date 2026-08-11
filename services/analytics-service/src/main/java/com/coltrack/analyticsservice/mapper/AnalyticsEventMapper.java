package com.coltrack.analyticsservice.mapper;

import com.coltrack.analyticsservice.entity.AnalyticsEventEntity;
import com.coltrack.events.analytics.AnalyticsEvent;
import org.springframework.stereotype.Component;

import java.util.HashMap;

@Component
public class AnalyticsEventMapper {

    public AnalyticsEventEntity toEntity(AnalyticsEvent event) {
        return AnalyticsEventEntity.builder()
                .eventId(event.eventId())
                .schemaVersion(event.schemaVersion() == null ? 1 : event.schemaVersion())
                .eventType(event.eventType())
                .cameraId(event.cameraId())
                .trackId(event.trackId())
                .objectType(event.objectType())
                .confidence(event.confidence())
                .frameNumber(event.frameNumber())
                .videoTimeSeconds(event.videoTimeSeconds())
                .occurredAt(event.occurredAt())
                .attributes(event.attributes() == null
                        ? new HashMap<>()
                        : new HashMap<>(event.attributes()))
                .build();
    }
}
