package com.coltrack.events.analytics;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AnalyticsJob(
        UUID jobId,
        Integer schemaVersion,
        String eventType,
        String jobType,
        UUID cameraId,
        UUID recordingId,
        AnalyticsSource source,
        AnalyticsProfile profile,
        OffsetDateTime occurredAt
) {
}
