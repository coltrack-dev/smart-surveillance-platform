package com.coltrack.recordingservice.service;

import com.coltrack.recordingservice.config.S3Properties;
import com.coltrack.recordingservice.config.S3StoragePolicyProperties;
import com.coltrack.recordingservice.dto.S3ReconciliationProblemResponse;
import com.coltrack.recordingservice.dto.S3ReconciliationResponse;
import com.coltrack.recordingservice.model.RecordingObjectEntity;
import com.coltrack.recordingservice.model.S3ObjectCleanupStatus;
import com.coltrack.recordingservice.model.S3ObjectVerificationStatus;
import com.coltrack.recordingservice.model.S3ReconciliationIssueEntity;
import com.coltrack.recordingservice.model.S3ReconciliationProblemType;
import com.coltrack.recordingservice.model.S3ReconciliationRunEntity;
import com.coltrack.recordingservice.repository.RecordingObjectRepository;
import com.coltrack.recordingservice.repository.S3ReconciliationIssueRepository;
import com.coltrack.recordingservice.repository.S3ReconciliationRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class S3ReconciliationService {

    private static final Set<S3ObjectVerificationStatus> PROBLEM_STATUSES = EnumSet.of(
            S3ObjectVerificationStatus.MISSING,
            S3ObjectVerificationStatus.SIZE_MISMATCH,
            S3ObjectVerificationStatus.ERROR
    );

    private final RecordingObjectRepository recordingObjectRepository;
    private final S3ReconciliationIssueRepository issueRepository;
    private final S3ReconciliationRunRepository runRepository;
    private final S3StorageService s3StorageService;
    private final S3Properties s3Properties;
    private final S3StoragePolicyProperties policy;
    private final DistributedLockService distributedLockService;
    private final AtomicBoolean reconciliationRunning = new AtomicBoolean(false);

    public S3ReconciliationResponse run() {
        return distributedLockService.execute(
                DistributedLockService.S3_RECONCILIATION, "S3 reconciliation", this::runLocked);
    }

    private S3ReconciliationResponse runLocked() {
        if (!s3Properties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "S3 storage is disabled");
        }
        if (!reconciliationRunning.compareAndSet(false, true)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "S3 reconciliation is already running");
        }

        try {
            Instant checkedAt = Instant.now();
            Instant graceCutoff = checkedAt.minus(policy.getReconciliationGracePeriod());
            List<RecordingObjectEntity> databaseObjects = recordingObjectRepository.findAllActive();
            Set<String> activeKeys = databaseObjects.stream()
                    .map(RecordingObjectEntity::getS3Key)
                    .collect(Collectors.toSet());

            long verified = 0;
            long missing = 0;
            long sizeMismatch = 0;
            long errors = 0;
            long catalogedBytes = 0;
            long actualBytes = 0;

            for (RecordingObjectEntity object : databaseObjects) {
                catalogedBytes += size(object.getSizeBytes());
                try {
                    var actual = s3StorageService.inspectObject(object.getS3Key());
                    if (actual.isEmpty()) {
                        if (object.getVerificationStatus() == S3ObjectVerificationStatus.ACKNOWLEDGED) {
                            continue;
                        }
                        if (isInsideGracePeriod(object.getUploadedAt(), graceCutoff)) {
                            continue;
                        }
                        mark(object, S3ObjectVerificationStatus.MISSING, null, null, checkedAt);
                        missing++;
                        continue;
                    }
                    long actualSize = actual.get().sizeBytes();
                    actualBytes += actualSize;
                    if (object.getSizeBytes() != null && object.getSizeBytes() != actualSize) {
                        mark(
                                object,
                                S3ObjectVerificationStatus.SIZE_MISMATCH,
                                actualSize,
                                "Cataloged and actual object sizes differ",
                                checkedAt
                        );
                        sizeMismatch++;
                    } else {
                        mark(object, S3ObjectVerificationStatus.VERIFIED, actualSize, null, checkedAt);
                        verified++;
                    }
                } catch (RuntimeException exception) {
                    mark(
                            object,
                            S3ObjectVerificationStatus.ERROR,
                            null,
                            safeMessage(exception),
                            checkedAt
                    );
                    errors++;
                }
            }

            List<S3StorageService.StoredObjectMetadata> listedObjects =
                    s3StorageService.listRecordingObjects();
            Set<String> detectedIssueKeys = new HashSet<>();
            long orphans = 0;
            long deleteIncomplete = 0;
            for (S3StorageService.StoredObjectMetadata object : listedObjects) {
                if (activeKeys.contains(object.key())) {
                    continue;
                }
                actualBytes += object.sizeBytes();
                if (isInsideGracePeriod(object.lastModified(), graceCutoff)) {
                    continue;
                }
                List<RecordingObjectEntity> catalogEntries =
                        recordingObjectRepository.findByS3Key(object.key());
                S3ReconciliationProblemType type = catalogEntries.stream()
                        .anyMatch(entry -> entry.getCleanupStatus() == S3ObjectCleanupStatus.DELETED)
                        ? S3ReconciliationProblemType.DELETE_INCOMPLETE
                        : S3ReconciliationProblemType.ORPHAN;
                upsertIssue(object, type, checkedAt);
                detectedIssueKeys.add(object.key());
                if (type == S3ReconciliationProblemType.DELETE_INCOMPLETE) {
                    deleteIncomplete++;
                } else {
                    orphans++;
                }
            }
            resolveDisappearedIssues(detectedIssueKeys, checkedAt);

            S3ReconciliationRunEntity run = runRepository.save(
                    S3ReconciliationRunEntity.builder()
                            .id(UUID.randomUUID())
                            .status(errors == 0 ? "COMPLETED" : "PARTIAL")
                            .databaseObjects(databaseObjects.size())
                            .listedObjects(listedObjects.size())
                            .verifiedObjects(verified)
                            .missingObjects(missing)
                            .sizeMismatchObjects(sizeMismatch)
                            .verificationErrors(errors)
                            .orphanObjects(orphans)
                            .deleteIncompleteObjects(deleteIncomplete)
                            .catalogedBytes(catalogedBytes)
                            .actualBytes(actualBytes)
                            .checkedAt(checkedAt)
                            .build()
            );
            return toResponse(run, getProblems());
        } finally {
            reconciliationRunning.set(false);
        }
    }

    public S3ReconciliationResponse getLatest() {
        return runRepository.findTopByOrderByCheckedAtDesc()
                .map(run -> toResponse(run, getProblems()))
                .orElseGet(() -> new S3ReconciliationResponse(
                        "NEVER_RUN", 0, 0, 0, 0, 0, 0,
                        0, 0, 0, 0, null, getProblems()
                ));
    }

    public List<S3ReconciliationProblemResponse> getProblems() {
        List<S3ReconciliationProblemResponse> problems = new ArrayList<>();
        recordingObjectRepository
                .findActiveProblems(PROBLEM_STATUSES)
                .forEach(object -> problems.add(new S3ReconciliationProblemResponse(
                        object.getVerificationStatus().name(),
                        object.getRecordingId(),
                        object.getS3Key(),
                        object.getSizeBytes(),
                        object.getActualSizeBytes(),
                        object.getVerificationError(),
                        object.getVerifiedAt()
                )));
        issueRepository.findByResolvedAtIsNullOrderByLastCheckedAtDesc()
                .forEach(issue -> problems.add(new S3ReconciliationProblemResponse(
                        issue.getProblemType().name(),
                        null,
                        issue.getS3Key(),
                        null,
                        issue.getActualSizeBytes(),
                        issue.getDetails(),
                        issue.getFirstDetectedAt()
                )));
        return List.copyOf(problems);
    }

    @Transactional
    public void acknowledge(String s3Key) {
        var object = recordingObjectRepository.findActiveByS3Key(s3Key);
        if (object.isPresent()) {
            if (!PROBLEM_STATUSES.contains(object.get().getVerificationStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "S3 object has no active problem");
            }
            object.get().setVerificationStatus(S3ObjectVerificationStatus.ACKNOWLEDGED);
            object.get().setVerificationError("Acknowledged by operator");
            object.get().setVerifiedAt(Instant.now());
            recordingObjectRepository.saveAndFlush(object.get());
            return;
        }
        S3ReconciliationIssueEntity issue = issueRepository.findByS3Key(s3Key)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "S3 problem not found"));
        issue.setResolvedAt(Instant.now());
        issue.setLastCheckedAt(Instant.now());
        issue.setResolution("ACKNOWLEDGED");
        issueRepository.saveAndFlush(issue);
    }

    @Transactional
    public long deleteOrphan(String s3Key) {
        if (!policy.isDeletionEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "S3 deletion is disabled");
        }
        return distributedLockService.execute(
                DistributedLockService.S3_CLEANUP, "S3 cleanup", () -> deleteOrphanLocked(s3Key));
    }

    private long deleteOrphanLocked(String s3Key) {
        S3ReconciliationIssueEntity issue = issueRepository.findByS3Key(s3Key)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "S3 orphan not found"));
        if (issue.getResolvedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "S3 problem is already resolved");
        }
        if (issue.getProblemType() != S3ReconciliationProblemType.ORPHAN
                && issue.getProblemType() != S3ReconciliationProblemType.DELETE_INCOMPLETE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "S3 problem is not an orphan object");
        }
        String prefix = s3Properties.getPrefix() == null ? "" : s3Properties.getPrefix().strip();
        if (prefix.isBlank() || !s3Key.startsWith(prefix.endsWith("/") ? prefix : prefix + "/")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Refusing to delete an object outside RECORDING_S3_PREFIX");
        }
        long freed = s3StorageService.deleteObjectCompletely(s3Key);
        issue.setResolvedAt(Instant.now());
        issue.setLastCheckedAt(Instant.now());
        issue.setResolution("DELETED");
        issueRepository.saveAndFlush(issue);
        return freed;
    }

    private void mark(
            RecordingObjectEntity object,
            S3ObjectVerificationStatus status,
            Long actualSize,
            String error,
            Instant checkedAt
    ) {
        object.setVerificationStatus(status);
        object.setActualSizeBytes(actualSize);
        object.setVerificationError(error);
        object.setVerifiedAt(checkedAt);
        recordingObjectRepository.save(object);
    }

    private void upsertIssue(
            S3StorageService.StoredObjectMetadata object,
            S3ReconciliationProblemType type,
            Instant checkedAt
    ) {
        S3ReconciliationIssueEntity issue = issueRepository.findByS3Key(object.key())
                .orElseGet(() -> S3ReconciliationIssueEntity.builder()
                        .id(UUID.randomUUID())
                        .s3Key(object.key())
                        .firstDetectedAt(checkedAt)
                        .build());
        issue.setProblemType(type);
        issue.setActualSizeBytes(object.sizeBytes());
        issue.setDetails(type == S3ReconciliationProblemType.ORPHAN
                ? "Object exists in S3 but is absent from the recording catalog"
                : "Catalog marks the object deleted but it still exists in S3");
        issue.setLastCheckedAt(checkedAt);
        issue.setResolvedAt(null);
        issueRepository.save(issue);
    }

    private void resolveDisappearedIssues(Set<String> detectedIssueKeys, Instant checkedAt) {
        for (S3ReconciliationIssueEntity issue
                : issueRepository.findByResolvedAtIsNullOrderByLastCheckedAtDesc()) {
            if (!detectedIssueKeys.contains(issue.getS3Key())) {
                issue.setResolvedAt(checkedAt);
                issue.setLastCheckedAt(checkedAt);
                issueRepository.save(issue);
            }
        }
    }

    private boolean isInsideGracePeriod(Instant timestamp, Instant graceCutoff) {
        return timestamp != null && timestamp.isAfter(graceCutoff);
    }

    private long size(Long value) {
        return value == null ? 0 : value;
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null ? exception.getClass().getSimpleName() : message;
    }

    private S3ReconciliationResponse toResponse(
            S3ReconciliationRunEntity run,
            List<S3ReconciliationProblemResponse> problems
    ) {
        return new S3ReconciliationResponse(
                run.getStatus(),
                run.getDatabaseObjects(),
                run.getListedObjects(),
                run.getVerifiedObjects(),
                run.getMissingObjects(),
                run.getSizeMismatchObjects(),
                run.getVerificationErrors(),
                run.getOrphanObjects(),
                run.getDeleteIncompleteObjects(),
                run.getCatalogedBytes(),
                run.getActualBytes(),
                run.getCheckedAt(),
                problems
        );
    }
}
