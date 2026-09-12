package com.coltrack.recordingservice.dto;

import com.coltrack.recordingservice.model.RecordingCleanupStatus;

import java.util.UUID;

public record StorageCleanupResultItemResponse(
        UUID recordingId,
        RecordingCleanupStatus status,
        long freedBytes,
        String error
) {
}
