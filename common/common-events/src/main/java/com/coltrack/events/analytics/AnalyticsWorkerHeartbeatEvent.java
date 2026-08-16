package com.coltrack.events.analytics;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AnalyticsWorkerHeartbeatEvent(
        UUID eventId,
        Integer schemaVersion,
        String eventType,
        String workerId,
        String status,
        Integer activeJobs,
        Integer maxJobs,
        String host,
        String platform,
        Boolean cudaAvailable,
        Integer cudaDeviceCount,
        String gpuName,
        OffsetDateTime occurredAt
) {
}
