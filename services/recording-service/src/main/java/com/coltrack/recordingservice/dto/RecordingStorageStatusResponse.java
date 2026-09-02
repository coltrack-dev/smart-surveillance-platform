package com.coltrack.recordingservice.dto;

public record RecordingStorageStatusResponse(
        long totalBytes,
        long usableBytes,
        long usedBytes,
        long catalogedRecordingBytes,
        double usedPercent
) {
}
