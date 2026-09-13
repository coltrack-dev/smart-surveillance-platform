package com.coltrack.recordingservice.dto;

import java.time.Instant;
import java.util.List;

public record S3CleanupPreviewResponse(
        boolean cleanupRequired,
        List<String> reasons,
        long currentBytes,
        long maximumBytes,
        long targetBytes,
        long bytesToFree,
        long candidateBytes,
        int candidateCount,
        boolean enoughEligibleData,
        Instant checkedAt,
        List<S3CleanupCandidateResponse> candidates
) {
}
