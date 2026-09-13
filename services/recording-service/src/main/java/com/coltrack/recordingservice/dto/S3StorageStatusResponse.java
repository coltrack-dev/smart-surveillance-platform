package com.coltrack.recordingservice.dto;

import java.time.Instant;

public record S3StorageStatusResponse(
        boolean enabled,
        String bucket,
        String prefix,
        long usedBytes,
        long maximumBytes,
        long targetBytes,
        long remainingQuotaBytes,
        long activeObjectCount,
        long recordingCount,
        long protectedBytes,
        double usedPercent,
        boolean cleanupRequired,
        Instant checkedAt
) {
}
