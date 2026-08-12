package com.coltrack.analyticsservice.mapper;

import com.coltrack.analyticsservice.entity.AnalyticsEventEntity;
import com.coltrack.events.analytics.AnalyticsEvent;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class AnalyticsEventMapper {

    public AnalyticsEventEntity toEntity(AnalyticsEvent event) {
        Map<String, Object> attributes =
                event.attributes() == null
                        ? new HashMap<>()
                        : new HashMap<>(event.attributes());

        return AnalyticsEventEntity.builder()
                .eventId(event.eventId())
                .schemaVersion(
                        event.schemaVersion() == null
                                ? 1
                                : event.schemaVersion()
                )
                .eventType(event.eventType())
                .cameraId(event.cameraId())
                .trackId(event.trackId())
                .recordingId(event.recordingId())
                .objectType(event.objectType())
                .confidence(event.confidence())
                .frameNumber(event.frameNumber())
                .videoTimeSeconds(event.videoTimeSeconds())
                .occurredAt(event.occurredAt())
                .snapshotUrl(getString(attributes, "snapshotUrl"))
                .clipUrl(getString(attributes, "clipUrl"))
                .attributes(attributes)
                .build();
    }

    private String getString(
            Map<String, Object> attributes,
            String key
    ) {
        Object value = attributes.get(key);

        return value instanceof String stringValue
                && !stringValue.isBlank()
                ? stringValue
                : null;
    }
}
