package com.coltrack.recordingservice.dto;

import java.time.Instant;
import java.util.List;

public record S3CleanupRunResponse(
        int attemptedCount,
        int deletedCount,
        int failedCount,
        long freedBytes,
        boolean targetReached,
        long remainingBytes,
        Instant finishedAt,
        List<S3CleanupResultItemResponse> results
) {
}
