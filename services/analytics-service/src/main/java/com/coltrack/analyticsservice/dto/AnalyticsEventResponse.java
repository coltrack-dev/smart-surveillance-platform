package com.coltrack.analyticsservice.dto;

import com.coltrack.analyticsservice.entity.AnalyticsEventEntity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record AnalyticsEventResponse(
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
        OffsetDateTime receivedAt,
        UUID recordingId,
        String snapshotUrl,
        String clipUrl,
        Map<String, Object> attributes
) {
    public static AnalyticsEventResponse fromEntity(AnalyticsEventEntity entity) {
        return new AnalyticsEventResponse(
                entity.getEventId(),
                entity.getSchemaVersion(),
                entity.getEventType(),
                entity.getCameraId(),
                entity.getTrackId(),
                entity.getObjectType(),
                entity.getConfidence(),
                entity.getFrameNumber(),
                entity.getVideoTimeSeconds(),
                entity.getOccurredAt(),
                entity.getReceivedAt(),
                entity.getRecordingId(),
                entity.getSnapshotUrl(),
                entity.getClipUrl(),
                entity.getAttributes() == null ? Map.of() : Map.copyOf(entity.getAttributes())
        );
    }
}
