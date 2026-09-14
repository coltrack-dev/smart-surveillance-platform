package com.coltrack.recordingservice.service;

import com.coltrack.recordingservice.config.S3Properties;
import com.coltrack.recordingservice.config.S3StoragePolicyProperties;
import com.coltrack.recordingservice.dto.S3CleanupPreviewResponse;
import com.coltrack.recordingservice.model.RecordingEntity;
import com.coltrack.recordingservice.model.RecordingObjectEntity;
import com.coltrack.recordingservice.model.RecordingStatus;
import com.coltrack.recordingservice.model.RecordingStorageType;
import com.coltrack.recordingservice.model.S3ObjectVerificationStatus;
import com.coltrack.recordingservice.repository.RecordingObjectRepository;
import com.coltrack.recordingservice.repository.RecordingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3StorageManagementServiceTest {

    @Mock
    private RecordingRepository recordingRepository;
    @Mock
    private RecordingObjectRepository recordingObjectRepository;
    @Mock
    private RecordingStorageService recordingStorageService;
    @Mock
    private S3StorageService s3StorageService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private RecordingUsageGuard recordingUsageGuard;
    @Mock private CleanupHistoryService cleanupHistoryService;

    @Test
    void previewsExpiredS3OnlyRecording() {
        UUID recordingId = UUID.randomUUID();
        RecordingEntity recording = recording(recordingId);
        when(recordingObjectRepository.sumActiveSizeBytes()).thenReturn(200L);
        when(recordingRepository
                .findByProtectedFromDeletionFalseAndStatusInOrderByFinishedAtAsc(any()))
                .thenReturn(List.of(recording));
        when(recordingObjectRepository.findActiveByRecordingId(recordingId))
                .thenReturn(List.of(object(recordingId, 200L)));
        when(recordingStorageService.hasRecordingFiles(recording.getFilePath()))
                .thenReturn(false);

        S3CleanupPreviewResponse preview = service(false, false).preview();

        assertTrue(preview.cleanupRequired());
        assertEquals(1, preview.candidateCount());
        assertEquals(200, preview.candidateBytes());
        assertEquals(RecordingStorageType.S3, preview.candidates().getFirst().storageType());
        assertEquals(List.of("S3_RETENTION_EXPIRED"), preview.reasons());
    }

    @Test
    void excludesHybridRecordingByDefault() {
        UUID recordingId = UUID.randomUUID();
        RecordingEntity recording = recording(recordingId);
        when(recordingObjectRepository.sumActiveSizeBytes()).thenReturn(200L);
        when(recordingRepository
                .findByProtectedFromDeletionFalseAndStatusInOrderByFinishedAtAsc(any()))
                .thenReturn(List.of(recording));
        when(recordingObjectRepository.findActiveByRecordingId(recordingId))
                .thenReturn(List.of(object(recordingId, 200L)));
        when(recordingStorageService.hasRecordingFiles(recording.getFilePath()))
                .thenReturn(true);

        S3CleanupPreviewResponse preview = service(false, false).preview();

        assertEquals(0, preview.candidateCount());
        assertTrue(preview.cleanupRequired());
    }

    @Test
    void refusesDeletionUntilExplicitlyEnabled() {
        assertThrows(ResponseStatusException.class, () -> service(false, false).runCleanup());
    }

    private S3StorageManagementService service(boolean deletionEnabled, boolean deleteHybrid) {
        S3StoragePolicyProperties policy = new S3StoragePolicyProperties();
        policy.setMaximumSize(DataSize.ofBytes(1_000));
        policy.setCleanupTargetPercent(90);
        policy.setRetentionDays(30);
        policy.setMaxRecordingsPerRun(100);
        policy.setDeletionEnabled(deletionEnabled);
        policy.setDeleteHybridEnabled(deleteHybrid);
        policy.setRequireVerifiedBeforeDeletion(true);

        S3Properties properties = new S3Properties();
        properties.setEnabled(true);
        properties.setBucket("recordings-test");
        properties.setPrefix("recordings");

        return new S3StorageManagementService(
                recordingRepository,
                recordingObjectRepository,
                recordingStorageService,
                s3StorageService,
                properties,
                policy,
                distributedLockService,
                recordingUsageGuard,
                cleanupHistoryService
        );
    }

    private RecordingEntity recording(UUID id) {
        return RecordingEntity.builder()
                .id(id)
                .cameraId(UUID.randomUUID())
                .filePath("/data/recordings/recording-1")
                .startedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .finishedAt(Instant.parse("2026-01-01T01:00:00Z"))
                .status(RecordingStatus.STOPPED)
                .build();
    }

    private RecordingObjectEntity object(UUID recordingId, long sizeBytes) {
        return RecordingObjectEntity.builder()
                .id(UUID.randomUUID())
                .recordingId(recordingId)
                .s3Key("recordings/segment.mkv")
                .sizeBytes(sizeBytes)
                .sequenceNumber(0)
                .verificationStatus(S3ObjectVerificationStatus.VERIFIED)
                .build();
    }
}
