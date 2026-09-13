package com.coltrack.recordingservice.dto;

import java.time.Instant;
import java.util.List;

public record S3ReconciliationResponse(
        String status,
        long databaseObjects,
        long listedObjects,
        long verifiedObjects,
        long missingObjects,
        long sizeMismatchObjects,
        long verificationErrors,
        long orphanObjects,
        long deleteIncompleteObjects,
        long catalogedBytes,
        long actualBytes,
        Instant checkedAt,
        List<S3ReconciliationProblemResponse> problems
) {
}
