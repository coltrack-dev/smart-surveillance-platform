package com.coltrack.recordingservice.dto;

import java.time.Instant;
import java.util.UUID;

public record S3ReconciliationProblemResponse(
        String type,
        UUID recordingId,
        String s3Key,
        Long catalogedBytes,
        Long actualBytes,
        String details,
        Instant detectedAt
) {
}
