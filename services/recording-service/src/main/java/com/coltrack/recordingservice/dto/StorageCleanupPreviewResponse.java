package com.coltrack.recordingservice.dto;

import java.time.Instant;
import java.util.List;

public record StorageCleanupPreviewResponse(
        boolean cleanupRequired,
        List<String> reasons,
        long currentRecordingBytes,
        long maximumLocalBytes,
        long targetRecordingBytes,
        long bytesToFree,
        long candidateBytes,
        int candidateCount,
        boolean enoughEligibleData,
        Instant checkedAt,
        List<StorageCleanupCandidateResponse> candidates
) {
}
