package com.coltrack.recordingservice.service;

import com.coltrack.recordingservice.config.RecordingStoragePolicyProperties;
import com.coltrack.recordingservice.dto.StorageCleanupCandidateResponse;
import com.coltrack.recordingservice.dto.StorageCleanupPreviewResponse;
import com.coltrack.recordingservice.dto.StorageCleanupResultItemResponse;
import com.coltrack.recordingservice.dto.StorageCleanupRunResponse;
import com.coltrack.recordingservice.dto.StoragePolicyResponse;
import com.coltrack.recordingservice.model.RecordingCleanupStatus;
import com.coltrack.recordingservice.model.RecordingEntity;
import com.coltrack.recordingservice.model.RecordingObjectEntity;
import com.coltrack.recordingservice.model.RecordingStatus;
import com.coltrack.recordingservice.model.RecordingStorageType;
import com.coltrack.recordingservice.repository.RecordingObjectRepository;
import com.coltrack.recordingservice.repository.RecordingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
@Slf4j
public class RecordingStorageCleanupService {

    private static final Set<RecordingStatus> FINISHED_STATUSES =
            EnumSet.of(
                    RecordingStatus.STOPPED,
                    RecordingStatus.COMPLETED,
                    RecordingStatus.FAILED
            );

    private final RecordingRepository recordingRepository;
    private final RecordingObjectRepository recordingObjectRepository;
    private final RecordingStorageService recordingStorageService;
    private final RecordingStoragePolicyProperties policy;
    private final AtomicBoolean cleanupRunning = new AtomicBoolean(false);

    public StoragePolicyResponse getPolicy() {
        long maximumLocalBytes = policy.getMaximumLocalSize().toBytes();
        return new StoragePolicyResponse(
                maximumLocalBytes,
                policy.getCleanupTargetPercent(),
                percentage(maximumLocalBytes, policy.getCleanupTargetPercent()),
                policy.getRetentionDays(),
                policy.getMinimumFreePercent(),
                policy.getEmergencyFreePercent(),
                policy.getMaxRecordingsPerRun(),
                policy.isDeleteLocalOnlyEnabled()
        );
    }

    public StorageCleanupPreviewResponse preview() {
        RecordingStorageService.StorageSnapshot snapshot =
                recordingStorageService.getSnapshot();
        long maximumLocalBytes = policy.getMaximumLocalSize().toBytes();
        long targetRecordingBytes = percentage(
                maximumLocalBytes,
                policy.getCleanupTargetPercent()
        );
        Instant retentionCutoff = Instant.now()
                .minus(policy.getRetentionDays(), ChronoUnit.DAYS);

        List<String> globalReasons = new ArrayList<>();
        long capacityBytesToFree = 0;

        if (snapshot.recordingBytes() > maximumLocalBytes) {
            globalReasons.add("MAXIMUM_LOCAL_SIZE_EXCEEDED");
            capacityBytesToFree = Math.max(
                    capacityBytesToFree,
                    snapshot.recordingBytes() - targetRecordingBytes
            );
        }

        double freePercent = percent(snapshot.usableBytes(), snapshot.totalBytes());
        if (freePercent <= policy.getMinimumFreePercent()) {
            globalReasons.add("MINIMUM_FREE_SPACE_BREACHED");
            long targetUsableBytes = percentage(
                    snapshot.totalBytes(),
                    policy.getMinimumFreePercent()
            );
            capacityBytesToFree = Math.max(
                    capacityBytesToFree,
                    Math.max(0, targetUsableBytes - snapshot.usableBytes())
            );
        }
        if (freePercent <= policy.getEmergencyFreePercent()) {
            globalReasons.add("EMERGENCY_FREE_SPACE_BREACHED");
        }

        List<Candidate> eligible = loadEligibleCandidates(retentionCutoff);
        List<Candidate> selected = new ArrayList<>();
        long selectedBytes = 0;
        long retentionBytesToFree = eligible.stream()
                .filter(candidate -> candidate.reasons().contains("RETENTION_EXPIRED"))
                .mapToLong(Candidate::localBytes)
                .sum();

        for (Candidate candidate : eligible) {
            if (selected.size() >= policy.getMaxRecordingsPerRun()) {
                break;
            }
            boolean expired = candidate.reasons().contains("RETENTION_EXPIRED");
            boolean capacityRequired = selectedBytes < capacityBytesToFree;
            if (!expired && !capacityRequired) {
                continue;
            }
            selected.add(candidate.withCapacityReasons(globalReasons));
            selectedBytes += candidate.localBytes();
        }

        boolean retentionRequired = eligible.stream()
                .anyMatch(candidate -> candidate.reasons().contains("RETENTION_EXPIRED"));
        if (retentionRequired) {
            globalReasons.add(0, "RETENTION_EXPIRED");
        }

        long bytesToFree = Math.max(capacityBytesToFree, retentionBytesToFree);

        return new StorageCleanupPreviewResponse(
                !selected.isEmpty(),
                List.copyOf(globalReasons),
                snapshot.recordingBytes(),
                maximumLocalBytes,
                targetRecordingBytes,
                bytesToFree,
                selectedBytes,
                selected.size(),
                selectedBytes >= bytesToFree,
                snapshot.checkedAt(),
                selected.stream().map(Candidate::toResponse).toList()
        );
    }

    public StorageCleanupRunResponse runCleanup() {
        if (!cleanupRunning.compareAndSet(false, true)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Storage cleanup is already running"
            );
        }

        try {
            StorageCleanupPreviewResponse preview = preview();
            List<StorageCleanupResultItemResponse> results = new ArrayList<>();
            long freedBytes = 0;
            int deletedCount = 0;
            int failedCount = 0;

            for (StorageCleanupCandidateResponse candidate : preview.candidates()) {
                StorageCleanupResultItemResponse result = deleteCandidate(candidate);
                results.add(result);
                freedBytes += result.freedBytes();
                if (result.status() == RecordingCleanupStatus.DELETE_FAILED) {
                    failedCount++;
                } else {
                    deletedCount++;
                }
            }

            RecordingStorageService.StorageSnapshot remaining =
                    recordingStorageService.getSnapshot();
            double remainingFreePercent = percent(
                    remaining.usableBytes(),
                    remaining.totalBytes()
            );
            boolean targetReached = !preview().cleanupRequired();

            return new StorageCleanupRunResponse(
                    results.size(),
                    deletedCount,
                    failedCount,
                    freedBytes,
                    targetReached,
                    remaining.recordingBytes(),
                    remainingFreePercent,
                    Instant.now(),
                    List.copyOf(results)
            );
        } finally {
            cleanupRunning.set(false);
        }
    }

    private List<Candidate> loadEligibleCandidates(Instant retentionCutoff) {
        return recordingRepository
                .findByProtectedFromDeletionFalseAndStatusInOrderByFinishedAtAsc(
                        FINISHED_STATUSES
                )
                .stream()
                .filter(this::hasEligibleCleanupStatus)
                .map(recording -> toCandidate(recording, retentionCutoff))
                .flatMap(Optional::stream)
                .filter(candidate -> candidate.localBytes() > 0)
                .filter(candidate -> policy.isDeleteLocalOnlyEnabled()
                        || candidate.storageType() == RecordingStorageType.HYBRID)
                .sorted(Comparator
                        .comparing((Candidate candidate) ->
                                candidate.storageType() != RecordingStorageType.HYBRID)
                        .thenComparing(Candidate::finishedAt))
                .toList();
    }

    private boolean hasEligibleCleanupStatus(RecordingEntity recording) {
        return recording.getCleanupStatus() == null
                || recording.getCleanupStatus() == RecordingCleanupStatus.AVAILABLE
                || recording.getCleanupStatus() == RecordingCleanupStatus.DELETE_FAILED;
    }

    private Optional<Candidate> toCandidate(
            RecordingEntity recording,
            Instant retentionCutoff
    ) {
        long localBytes;
        try {
            localBytes = recordingStorageService.getRecordingDirectorySize(
                    recording.getFilePath()
            );
        } catch (RuntimeException exception) {
            log.warn(
                    "Skipping unmanaged recording path recordingId={} path={}",
                    recording.getId(),
                    recording.getFilePath(),
                    exception
            );
            return Optional.empty();
        }
        boolean hasS3Copy = hasCompleteS3Copy(recording, localBytes);
        Instant finishedAt = recording.getFinishedAt() != null
                ? recording.getFinishedAt()
                : recording.getStartedAt();
        if (finishedAt == null) {
            finishedAt = Instant.now();
        }

        List<String> reasons = finishedAt.isBefore(retentionCutoff)
                ? List.of("RETENTION_EXPIRED")
                : List.of();
        RecordingStorageType storageType = hasS3Copy
                ? RecordingStorageType.HYBRID
                : RecordingStorageType.LOCAL;

        return Optional.of(new Candidate(
                recording.getId(),
                recording.getCameraId(),
                finishedAt,
                localBytes,
                storageType,
                reasons
        ));
    }

    private StorageCleanupResultItemResponse deleteCandidate(
            StorageCleanupCandidateResponse candidate
    ) {
        RecordingEntity recording = recordingRepository
                .findById(candidate.recordingId())
                .orElse(null);
        if (recording == null
                || recording.isProtectedFromDeletion()
                || !FINISHED_STATUSES.contains(recording.getStatus())) {
            return new StorageCleanupResultItemResponse(
                    candidate.recordingId(),
                    RecordingCleanupStatus.DELETE_FAILED,
                    0,
                    "Recording is no longer eligible for cleanup"
            );
        }

        long currentLocalBytes = recordingStorageService.getRecordingDirectorySize(
                recording.getFilePath()
        );
        boolean hasS3Copy = hasCompleteS3Copy(recording, currentLocalBytes);
        if (!hasS3Copy && !policy.isDeleteLocalOnlyEnabled()) {
            return new StorageCleanupResultItemResponse(
                    recording.getId(),
                    RecordingCleanupStatus.DELETE_FAILED,
                    0,
                    "Local-only deletion is disabled"
            );
        }

        recording.setCleanupStatus(RecordingCleanupStatus.DELETING);
        recordingRepository.saveAndFlush(recording);

        try {
            long deletedBytes = recordingStorageService.deleteRecordingDirectory(
                    recording.getFilePath()
            );
            RecordingCleanupStatus finalStatus = hasS3Copy
                    ? RecordingCleanupStatus.LOCAL_DELETED
                    : RecordingCleanupStatus.DELETED;
            recording.setCleanupStatus(finalStatus);
            recording.setDeletedAt(Instant.now());
            recording.setDeletionReason(String.join(",", candidate.reasons()));
            recordingRepository.saveAndFlush(recording);

            return new StorageCleanupResultItemResponse(
                    recording.getId(),
                    finalStatus,
                    deletedBytes,
                    null
            );
        } catch (RuntimeException exception) {
            recording.setCleanupStatus(RecordingCleanupStatus.DELETE_FAILED);
            recording.setDeletionReason(exception.getMessage());
            recordingRepository.saveAndFlush(recording);
            return new StorageCleanupResultItemResponse(
                    recording.getId(),
                    RecordingCleanupStatus.DELETE_FAILED,
                    0,
                    exception.getMessage()
            );
        }
    }

    private boolean hasCompleteS3Copy(
            RecordingEntity recording,
            long localBytes
    ) {
        List<RecordingObjectEntity> objects = recordingObjectRepository
                .findByRecordingIdOrderBySequenceNumberAsc(recording.getId());
        if (objects.isEmpty() || localBytes <= 0) {
            return false;
        }

        long objectBytes = objects.stream()
                .map(RecordingObjectEntity::getSizeBytes)
                .filter(java.util.Objects::nonNull)
                .mapToLong(Long::longValue)
                .sum();
        long expectedBytes = recording.getSizeBytes() != null
                ? recording.getSizeBytes()
                : localBytes;
        boolean segmentCountMatches = recording.getSegmentsCount() == null
                || recording.getSegmentsCount() == objects.size();
        return segmentCountMatches && objectBytes == expectedBytes;
    }

    private long percentage(long value, int percentage) {
        return Math.round(value * (percentage / 100.0));
    }

    private double percent(long value, long total) {
        return total == 0 ? 0 : value * 100.0 / total;
    }

    private record Candidate(
            java.util.UUID recordingId,
            java.util.UUID cameraId,
            Instant finishedAt,
            long localBytes,
            RecordingStorageType storageType,
            List<String> reasons
    ) {
        Candidate withCapacityReasons(List<String> capacityReasons) {
            List<String> combined = new ArrayList<>(reasons);
            capacityReasons.stream()
                    .filter(reason -> !combined.contains(reason))
                    .forEach(combined::add);
            return new Candidate(
                    recordingId,
                    cameraId,
                    finishedAt,
                    localBytes,
                    storageType,
                    List.copyOf(combined)
            );
        }

        StorageCleanupCandidateResponse toResponse() {
            return new StorageCleanupCandidateResponse(
                    recordingId,
                    cameraId,
                    finishedAt,
                    localBytes,
                    storageType,
                    reasons
            );
        }
    }
}
