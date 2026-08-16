package com.coltrack.events.analytics;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record AnalyticsJobStatusEvent(
        UUID eventId,
        Integer schemaVersion,
        String eventType,
        UUID jobId,
        String jobType,
        String workerId,
        UUID cameraId,
        UUID recordingId,
        String status,
        OffsetDateTime occurredAt,
        Map<String, Object> details
) {
}
