package com.coltrack.recordingservice.dto;

import com.coltrack.recordingservice.model.RecordingStorageType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record S3CleanupCandidateResponse(
        UUID recordingId,
        UUID cameraId,
        Instant finishedAt,
        long s3Bytes,
        int objectCount,
        RecordingStorageType storageType,
        List<String> reasons
) {
}
