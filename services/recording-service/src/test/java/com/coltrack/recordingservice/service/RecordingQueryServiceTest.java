package com.coltrack.recordingservice.service;

import com.coltrack.recordingservice.dto.RecordingResponse;
import com.coltrack.recordingservice.dto.RecordingStorageStatusResponse;
import com.coltrack.recordingservice.model.RecordingEntity;
import com.coltrack.recordingservice.model.RecordingStorageType;
import com.coltrack.recordingservice.model.StorageHealthStatus;
import com.coltrack.recordingservice.repository.RecordingObjectRepository;
import com.coltrack.recordingservice.repository.RecordingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecordingQueryServiceTest {

    @Mock
    private RecordingRepository recordingRepository;

    @Mock
    private RecordingObjectRepository recordingObjectRepository;

    @Mock
    private RecordingStorageService recordingStorageService;

    @Test
    void protectsRecordingAndReportsHybridStorage() {
        UUID recordingId = UUID.randomUUID();
        RecordingEntity recording = RecordingEntity.builder()
                .id(recordingId)
                .cameraId(UUID.randomUUID())
                .filePath("/recordings/test")
                .build();

        when(recordingRepository.findById(recordingId))
                .thenReturn(Optional.of(recording));
        when(recordingStorageService.hasRecordingFiles(recording.getFilePath()))
                .thenReturn(true);
        when(recordingObjectRepository.existsByRecordingId(recordingId))
                .thenReturn(true);

        RecordingResponse response = service().setProtected(recordingId, true);

        assertTrue(recording.isProtectedFromDeletion());
        assertTrue(response.protectedFromDeletion());
        assertEquals(RecordingStorageType.HYBRID, response.storageType());
        verify(recordingRepository).saveAndFlush(recording);
    }

    @Test
    void rejectsInvalidTimeRange() {
        Instant time = Instant.parse("2026-09-02T12:00:00Z");

        assertThrows(
                ResponseStatusException.class,
                () -> service().find(null, time, time, null, null, 0, 20)
        );
    }

    @Test
    void reportsStorageCapacityAndCatalogedSize() {
        Instant checkedAt = Instant.parse("2026-09-12T12:00:00Z");
        when(recordingStorageService.getSnapshot()).thenReturn(
                new RecordingStorageService.StorageSnapshot(
                        1000,
                        250,
                        750,
                        650,
                        StorageHealthStatus.WARNING,
                        30,
                        20,
                        checkedAt
                )
        );
        when(recordingRepository.sumCatalogedSizeBytes()).thenReturn(600L);
        when(recordingRepository.count()).thenReturn(4L);
        when(recordingRepository.countByProtectedFromDeletionTrue()).thenReturn(1L);

        RecordingStorageStatusResponse response = service().getStorageStatus();

        assertEquals(1000, response.totalBytes());
        assertEquals(250, response.usableBytes());
        assertEquals(750, response.usedBytes());
        assertEquals(650, response.recordingBytes());
        assertEquals(600, response.catalogedRecordingBytes());
        assertEquals(50, response.sizeDiscrepancyBytes());
        assertEquals(4, response.recordingCount());
        assertEquals(3, response.unprotectedRecordingCount());
        assertEquals(1, response.protectedRecordingCount());
        assertEquals(75.0, response.usedPercent());
        assertEquals(25.0, response.freePercent());
        assertEquals(StorageHealthStatus.WARNING, response.status());
        assertEquals(checkedAt, response.checkedAt());
    }

    private RecordingQueryService service() {
        return new RecordingQueryService(
                recordingRepository,
                recordingObjectRepository,
                recordingStorageService
        );
    }
}
