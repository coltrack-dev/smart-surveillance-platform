package com.coltrack.recordingservice.service;

import com.coltrack.recordingservice.config.S3Properties;
import com.coltrack.recordingservice.config.S3StoragePolicyProperties;
import com.coltrack.recordingservice.dto.S3CleanupCandidateResponse;
import com.coltrack.recordingservice.dto.S3CleanupPreviewResponse;
import com.coltrack.recordingservice.dto.S3CleanupResultItemResponse;
import com.coltrack.recordingservice.dto.S3CleanupRunResponse;
import com.coltrack.recordingservice.dto.S3StoragePolicyResponse;
import com.coltrack.recordingservice.dto.S3StorageStatusResponse;
import com.coltrack.recordingservice.model.RecordingCleanupStatus;
import com.coltrack.recordingservice.model.RecordingEntity;
import com.coltrack.recordingservice.model.RecordingObjectEntity;
import com.coltrack.recordingservice.model.RecordingStatus;
import com.coltrack.recordingservice.model.RecordingStorageType;
import com.coltrack.recordingservice.model.S3ObjectCleanupStatus;
import com.coltrack.recordingservice.repository.RecordingObjectRepository;
import com.coltrack.recordingservice.repository.RecordingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
public class S3StorageManagementService {

    private static final Set<RecordingStatus> FINISHED_STATUSES = EnumSet.of(
            RecordingStatus.STOPPED,
            RecordingStatus.COMPLETED,
            RecordingStatus.FAILED
    );

    private final RecordingRepository recordingRepository;
    private final RecordingObjectRepository recordingObjectRepository;
    private final RecordingStorageService recordingStorageService;
    private final S3StorageService s3StorageService;
    private final S3Properties s3Properties;
    private final S3StoragePolicyProperties policy;
    private final AtomicBoolean cleanupRunning = new AtomicBoolean(false);

    public S3StorageStatusResponse getStatus() {
        long usedBytes = recordingObjectRepository.sumActiveSizeBytes();
        long maximumBytes = policy.getMaximumSize().toBytes();
        long targetBytes = percentage(maximumBytes, policy.getCleanupTargetPercent());
        return new S3StorageStatusResponse(
                s3Properties.isEnabled(),
                s3Properties.getBucket(),
                normalizedPrefix(),
                usedBytes,
                maximumBytes,
                targetBytes,
                Math.max(0, maximumBytes - usedBytes),
                recordingObjectRepository.countActiveObjects(),
                recordingObjectRepository.countActiveRecordings(),
                recordingObjectRepository.sumProtectedActiveSizeBytes(),
                maximumBytes == 0 ? 0 : usedBytes * 100.0 / maximumBytes,
                usedBytes > maximumBytes,
                Instant.now()
        );
    }

    public S3StoragePolicyResponse getPolicy() {
        long maximumBytes = policy.getMaximumSize().toBytes();
        return new S3StoragePolicyResponse(
                maximumBytes,
                policy.getCleanupTargetPercent(),
                percentage(maximumBytes, policy.getCleanupTargetPercent()),
                policy.getRetentionDays(),
                policy.getMaxRecordingsPerRun(),
                policy.isDeletionEnabled(),
                policy.isDeleteHybridEnabled(),
                policy.isRequireVerifiedBeforeDeletion()
        );
    }

    public S3CleanupPreviewResponse preview() {
        long currentBytes = recordingObjectRepository.sumActiveSizeBytes();
        long maximumBytes = policy.getMaximumSize().toBytes();
        long targetBytes = percentage(maximumBytes, policy.getCleanupTargetPercent());
        long capacityBytesToFree = currentBytes > maximumBytes
                ? currentBytes - targetBytes
                : 0;
        Instant retentionCutoff = Instant.now()
                .minus(policy.getRetentionDays(), ChronoUnit.DAYS);

        List<Candidate> eligible = loadEligibleCandidates(retentionCutoff);
        long retentionBytesToFree = eligible.stream()
                .filter(candidate -> candidate.reasons().contains("S3_RETENTION_EXPIRED"))
                .mapToLong(Candidate::s3Bytes)
                .sum();
        long bytesToFree = Math.max(capacityBytesToFree, retentionBytesToFree);
        List<String> reasons = new ArrayList<>();
        if (retentionBytesToFree > 0) {
            reasons.add("S3_RETENTION_EXPIRED");
        }
        if (capacityBytesToFree > 0) {
            reasons.add("S3_QUOTA_EXCEEDED");
        }

        List<Candidate> selected = new ArrayList<>();
        long selectedBytes = 0;
        for (Candidate candidate : eligible) {
            if (selected.size() >= policy.getMaxRecordingsPerRun()) {
                break;
            }
            boolean expired = candidate.reasons().contains("S3_RETENTION_EXPIRED");
            if (!expired && selectedBytes >= capacityBytesToFree) {
                continue;
            }
            selected.add(candidate.withQuotaReason(capacityBytesToFree > 0));
            selectedBytes += candidate.s3Bytes();
        }

        boolean cleanupRequired = bytesToFree > 0;
        return new S3CleanupPreviewResponse(
                cleanupRequired,
                List.copyOf(reasons),
                currentBytes,
                maximumBytes,
                targetBytes,
                bytesToFree,
                selectedBytes,
                selected.size(),
                !cleanupRequired || selectedBytes >= bytesToFree,
                Instant.now(),
                selected.stream().map(Candidate::toResponse).toList()
        );
    }

    public S3CleanupRunResponse runCleanup() {
        if (!s3Properties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "S3 storage is disabled");
        }
        if (!policy.isDeletionEnabled()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "S3 deletion is disabled; verify the preview and enable recording.s3.management.deletion-enabled"
            );
        }
        if (!cleanupRunning.compareAndSet(false, true)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "S3 cleanup is already running");
        }

        try {
            S3CleanupPreviewResponse preview = preview();
            List<S3CleanupResultItemResponse> results = new ArrayList<>();
            long freedBytes = 0;
            int deletedCount = 0;
            int failedCount = 0;
            for (S3CleanupCandidateResponse candidate : preview.candidates()) {
                S3CleanupResultItemResponse result = deleteCandidate(candidate);
                results.add(result);
                freedBytes += result.freedBytes();
                if (result.status() == S3ObjectCleanupStatus.DELETED) {
                    deletedCount++;
                } else {
                    failedCount++;
                }
            }

            long remainingBytes = recordingObjectRepository.sumActiveSizeBytes();
            return new S3CleanupRunResponse(
                    results.size(),
                    deletedCount,
                    failedCount,
                    freedBytes,
                    !preview().cleanupRequired(),
                    remainingBytes,
                    Instant.now(),
                    List.copyOf(results)
            );
        } finally {
            cleanupRunning.set(false);
        }
    }

    private List<Candidate> loadEligibleCandidates(Instant retentionCutoff) {
        return recordingRepository
                .findByProtectedFromDeletionFalseAndStatusInOrderByFinishedAtAsc(FINISHED_STATUSES)
                .stream()
                .map(recording -> toCandidate(recording, retentionCutoff))
                .filter(candidate -> candidate != null && candidate.s3Bytes() > 0)
                .filter(candidate -> policy.isDeleteHybridEnabled()
                        || candidate.storageType() == RecordingStorageType.S3)
                .sorted(Comparator
                        .comparing((Candidate candidate) -> candidate.storageType() != RecordingStorageType.S3)
                        .thenComparing(Candidate::finishedAt))
                .toList();
    }

    private Candidate toCandidate(RecordingEntity recording, Instant retentionCutoff) {
        List<RecordingObjectEntity> objects = recordingObjectRepository
                .findActiveByRecordingId(recording.getId());
        if (objects.isEmpty()) {
            return null;
        }
        if (policy.isRequireVerifiedBeforeDeletion()
                && objects.stream().anyMatch(object ->
                        object.getVerificationStatus()
                                != com.coltrack.recordingservice.model.S3ObjectVerificationStatus.VERIFIED)) {
            return null;
        }
        long s3Bytes = objects.stream()
                .map(RecordingObjectEntity::getSizeBytes)
                .filter(java.util.Objects::nonNull)
                .mapToLong(Long::longValue)
                .sum();
        Instant finishedAt = recording.getFinishedAt() != null
                ? recording.getFinishedAt()
                : recording.getStartedAt();
        if (finishedAt == null) {
            return null;
        }
        boolean local = recordingStorageService.hasRecordingFiles(recording.getFilePath());
        return new Candidate(
                recording.getId(),
                recording.getCameraId(),
                finishedAt,
                s3Bytes,
                objects.size(),
                local ? RecordingStorageType.HYBRID : RecordingStorageType.S3,
                finishedAt.isBefore(retentionCutoff)
                        ? List.of("S3_RETENTION_EXPIRED")
                        : List.of()
        );
    }

    private S3CleanupResultItemResponse deleteCandidate(S3CleanupCandidateResponse candidate) {
        RecordingEntity recording = recordingRepository.findById(candidate.recordingId()).orElse(null);
        if (recording == null || recording.isProtectedFromDeletion()
                || !FINISHED_STATUSES.contains(recording.getStatus())) {
            return failed(candidate.recordingId(), 0, "Recording is no longer eligible for S3 cleanup");
        }
        boolean local = recordingStorageService.hasRecordingFiles(recording.getFilePath());
        if (local && !policy.isDeleteHybridEnabled()) {
            return failed(candidate.recordingId(), 0, "Deleting S3 copies of HYBRID recordings is disabled");
        }

        List<RecordingObjectEntity> objects = recordingObjectRepository
                .findActiveByRecordingId(recording.getId());
        if (policy.isRequireVerifiedBeforeDeletion()
                && objects.stream().anyMatch(object ->
                        object.getVerificationStatus()
                                != com.coltrack.recordingservice.model.S3ObjectVerificationStatus.VERIFIED)) {
            return failed(recording.getId(), 0, "S3 objects must be verified before deletion");
        }
        long freedBytes = 0;
        String lastError = null;
        for (RecordingObjectEntity object : objects) {
            object.setCleanupStatus(S3ObjectCleanupStatus.DELETING);
            object.setDeletionReason(String.join(",", candidate.reasons()));
            recordingObjectRepository.saveAndFlush(object);
            try {
                s3StorageService.deleteObject(object.getS3Key());
                object.setCleanupStatus(S3ObjectCleanupStatus.DELETED);
                object.setDeletedAt(Instant.now());
                freedBytes += object.getSizeBytes() == null ? 0 : object.getSizeBytes();
            } catch (RuntimeException exception) {
                object.setCleanupStatus(S3ObjectCleanupStatus.DELETE_FAILED);
                object.setDeletionReason(exception.getMessage());
                lastError = exception.getMessage();
            }
            recordingObjectRepository.saveAndFlush(object);
        }

        boolean anyActive = recordingObjectRepository.existsActiveByRecordingId(recording.getId());
        if (!anyActive && !local) {
            recording.setCleanupStatus(RecordingCleanupStatus.DELETED);
            recording.setDeletedAt(Instant.now());
            recording.setDeletionReason(String.join(",", candidate.reasons()));
            recordingRepository.saveAndFlush(recording);
        }
        return lastError == null
                ? new S3CleanupResultItemResponse(
                        recording.getId(), S3ObjectCleanupStatus.DELETED, freedBytes, null)
                : failed(recording.getId(), freedBytes, lastError);
    }

    private S3CleanupResultItemResponse failed(java.util.UUID recordingId, long freedBytes, String error) {
        return new S3CleanupResultItemResponse(
                recordingId, S3ObjectCleanupStatus.DELETE_FAILED, freedBytes, error
        );
    }

    private String normalizedPrefix() {
        return s3Properties.getPrefix() == null ? "" : s3Properties.getPrefix();
    }

    private long percentage(long value, int percent) {
        return Math.round(value * (percent / 100.0));
    }

    private record Candidate(
            java.util.UUID recordingId,
            java.util.UUID cameraId,
            Instant finishedAt,
            long s3Bytes,
            int objectCount,
            RecordingStorageType storageType,
            List<String> reasons
    ) {
        Candidate withQuotaReason(boolean add) {
            if (!add || reasons.contains("S3_QUOTA_EXCEEDED")) {
                return this;
            }
            List<String> combined = new ArrayList<>(reasons);
            combined.add("S3_QUOTA_EXCEEDED");
            return new Candidate(
                    recordingId, cameraId, finishedAt, s3Bytes,
                    objectCount, storageType, List.copyOf(combined)
            );
        }

        S3CleanupCandidateResponse toResponse() {
            return new S3CleanupCandidateResponse(
                    recordingId, cameraId, finishedAt, s3Bytes,
                    objectCount, storageType, reasons
            );
        }
    }
}
