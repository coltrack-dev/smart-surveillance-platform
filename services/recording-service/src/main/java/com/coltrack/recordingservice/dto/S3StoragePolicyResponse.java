package com.coltrack.recordingservice.dto;

public record S3StoragePolicyResponse(
        long maximumBytes,
        int cleanupTargetPercent,
        long cleanupTargetBytes,
        int retentionDays,
        int maxRecordingsPerRun,
        boolean deletionEnabled,
        boolean deleteHybridEnabled,
        boolean requireVerifiedBeforeDeletion,
        boolean automaticCleanupEnabled,
        long automaticCleanupDelaySeconds
) {
}
