package com.coltrack.recordingservice.dto;

import com.coltrack.recordingservice.model.S3ObjectCleanupStatus;

import java.util.UUID;

public record S3CleanupResultItemResponse(
        UUID recordingId,
        S3ObjectCleanupStatus status,
        long freedBytes,
        String error
) {
}
