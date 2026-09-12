package com.coltrack.recordingservice.dto;

import com.coltrack.recordingservice.model.RecordingStorageType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record StorageCleanupCandidateResponse(
        UUID recordingId,
        UUID cameraId,
        Instant finishedAt,
        long localBytes,
        RecordingStorageType storageType,
        List<String> reasons
) {
}
