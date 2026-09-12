package com.coltrack.recordingservice.service;

import com.coltrack.recordingservice.config.RecordingStoragePolicyProperties;
import com.coltrack.recordingservice.dto.StorageCleanupPreviewResponse;
import com.coltrack.recordingservice.model.RecordingEntity;
import com.coltrack.recordingservice.model.RecordingObjectEntity;
import com.coltrack.recordingservice.model.RecordingStatus;
import com.coltrack.recordingservice.model.StorageHealthStatus;
import com.coltrack.recordingservice.repository.RecordingObjectRepository;
import com.coltrack.recordingservice.repository.RecordingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.unit.DataSize;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecordingStorageCleanupServiceTest {

    @Mock
    private RecordingRepository recordingRepository;

    @Mock
    private RecordingObjectRepository recordingObjectRepository;

    @Mock
    private RecordingStorageService recordingStorageService;

    @Test
    void previewsExpiredHybridRecordingWithoutDeletingIt() {
        UUID recordingId = UUID.randomUUID();
        RecordingEntity recording = recording(recordingId, 200L);

        when(recordingStorageService.getSnapshot()).thenReturn(snapshot(200));
        when(recordingRepository
                .findByProtectedFromDeletionFalseAndStatusInOrderByFinishedAtAsc(any()))
                .thenReturn(List.of(recording));
        when(recordingStorageService.getRecordingDirectorySize(recording.getFilePath()))
                .thenReturn(200L);
        when(recordingObjectRepository
                .findByRecordingIdOrderBySequenceNumberAsc(recordingId))
                .thenReturn(List.of(object(recordingId, 200L)));

        StorageCleanupPreviewResponse preview = service(false).preview();

        assertTrue(preview.cleanupRequired());
        assertEquals(List.of("RETENTION_EXPIRED"), preview.reasons());
        assertEquals(1, preview.candidateCount());
        assertEquals(200, preview.candidateBytes());
        assertTrue(preview.enoughEligibleData());
    }

    @Test
    void excludesLocalOnlyRecordingByDefault() {
        UUID recordingId = UUID.randomUUID();
        RecordingEntity recording = recording(recordingId, 200L);

        when(recordingStorageService.getSnapshot()).thenReturn(snapshot(200));
        when(recordingRepository
                .findByProtectedFromDeletionFalseAndStatusInOrderByFinishedAtAsc(any()))
                .thenReturn(List.of(recording));
        when(recordingStorageService.getRecordingDirectorySize(recording.getFilePath()))
                .thenReturn(200L);
        when(recordingObjectRepository
                .findByRecordingIdOrderBySequenceNumberAsc(recordingId))
                .thenReturn(List.of());

        StorageCleanupPreviewResponse preview = service(false).preview();

        assertFalse(preview.cleanupRequired());
        assertEquals(0, preview.candidateCount());
    }

    private RecordingStorageCleanupService service(boolean deleteLocalOnly) {
        RecordingStoragePolicyProperties policy =
                new RecordingStoragePolicyProperties();
        policy.setMaximumLocalSize(DataSize.ofBytes(1_000));
        policy.setCleanupTargetPercent(90);
        policy.setRetentionDays(30);
        policy.setMinimumFreePercent(15);
        policy.setEmergencyFreePercent(5);
        policy.setMaxRecordingsPerRun(100);
        policy.setDeleteLocalOnlyEnabled(deleteLocalOnly);

        return new RecordingStorageCleanupService(
                recordingRepository,
                recordingObjectRepository,
                recordingStorageService,
                policy
        );
    }

    private RecordingEntity recording(UUID id, long sizeBytes) {
        return RecordingEntity.builder()
                .id(id)
                .cameraId(UUID.randomUUID())
                .filePath("/data/recordings/recording-1")
                .startedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .finishedAt(Instant.parse("2026-01-01T01:00:00Z"))
                .status(RecordingStatus.STOPPED)
                .segmentsCount(1)
                .sizeBytes(sizeBytes)
                .build();
    }

    private RecordingObjectEntity object(UUID recordingId, long sizeBytes) {
        return RecordingObjectEntity.builder()
                .id(UUID.randomUUID())
                .recordingId(recordingId)
                .sizeBytes(sizeBytes)
                .sequenceNumber(0)
                .build();
    }

    private RecordingStorageService.StorageSnapshot snapshot(long recordingBytes) {
        return new RecordingStorageService.StorageSnapshot(
                10_000,
                5_000,
                5_000,
                recordingBytes,
                StorageHealthStatus.HEALTHY,
                20,
                10,
                Instant.parse("2026-09-12T12:00:00Z")
        );
    }
}
