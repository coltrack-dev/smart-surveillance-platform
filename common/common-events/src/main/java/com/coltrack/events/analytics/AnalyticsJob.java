package com.coltrack.events.analytics;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AnalyticsJob(
        UUID jobId,
        Integer schemaVersion,
        String eventType,
        String jobType,
        String action,
        UUID cameraId,
        UUID recordingId,
        AnalyticsSource source,
        AnalyticsProfile profile,
        OffsetDateTime occurredAt
) {
}
