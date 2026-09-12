package com.coltrack.recordingservice.dto;

public record StoragePolicyResponse(
        long maximumLocalBytes,
        int cleanupTargetPercent,
        long cleanupTargetBytes,
        int retentionDays,
        int minimumFreePercent,
        int emergencyFreePercent,
        int maxRecordingsPerRun,
        boolean deleteLocalOnlyEnabled
) {
}
