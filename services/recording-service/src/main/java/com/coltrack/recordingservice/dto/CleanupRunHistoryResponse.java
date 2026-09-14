package com.coltrack.recordingservice.dto;

import java.time.Instant;
import java.util.UUID;

public record CleanupRunHistoryResponse(
        UUID id, String storageScope, String triggerType, String status,
        int attemptedCount, int deletedCount, int failedCount, long freedBytes,
        Instant startedAt, Instant finishedAt, String error
) {
}
