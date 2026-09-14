package com.coltrack.recordingservice.dto;

import java.util.UUID;

public record CleanupRunHistoryItemResponse(
        UUID id,
        UUID recordingId,
        String s3Key,
        String status,
        long freedBytes,
        String error
) {
}
