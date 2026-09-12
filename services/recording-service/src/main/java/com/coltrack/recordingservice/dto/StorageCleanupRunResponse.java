package com.coltrack.recordingservice.dto;

import java.time.Instant;
import java.util.List;

public record StorageCleanupRunResponse(
        int attemptedCount,
        int deletedCount,
        int failedCount,
        long freedBytes,
        boolean targetReached,
        long remainingRecordingBytes,
        double remainingFreePercent,
        Instant finishedAt,
        List<StorageCleanupResultItemResponse> results
) {
}
