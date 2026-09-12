package com.coltrack.recordingservice.dto;

import com.coltrack.recordingservice.model.StorageHealthStatus;

import java.time.Instant;

public record RecordingStorageStatusResponse(
        long totalBytes,
        long usableBytes,
        long usedBytes,
        long recordingBytes,
        long catalogedRecordingBytes,
        long sizeDiscrepancyBytes,
        long recordingCount,
        long unprotectedRecordingCount,
        long protectedRecordingCount,
        double usedPercent,
        double freePercent,
        StorageHealthStatus status,
        int warningThresholdPercent,
        int criticalThresholdPercent,
        Instant checkedAt
) {
}
