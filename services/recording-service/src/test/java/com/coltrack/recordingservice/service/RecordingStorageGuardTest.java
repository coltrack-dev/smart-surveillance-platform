package com.coltrack.recordingservice.service;

import com.coltrack.recordingservice.config.RecordingStoragePolicyProperties;
import com.coltrack.recordingservice.model.StorageHealthStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecordingStorageGuardTest {

    @Mock
    private RecordingStorageService recordingStorageService;

    @Test
    void rejectsNewRecordingAtLocalLimit() {
        RecordingStorageGuard guard = guard(1_000);
        when(recordingStorageService.getSnapshot()).thenReturn(snapshot(1_000, 5_000));

        assertThrows(ResponseStatusException.class, guard::assertRecordingCanStart);
    }

    @Test
    void permitsRecordingBelowLimits() {
        RecordingStorageGuard guard = guard(1_000);
        when(recordingStorageService.getSnapshot()).thenReturn(snapshot(500, 5_000));

        assertDoesNotThrow(guard::assertRecordingCanStart);
    }

    private RecordingStorageGuard guard(long maximumBytes) {
        RecordingStoragePolicyProperties policy =
                new RecordingStoragePolicyProperties();
        policy.setMaximumLocalSize(DataSize.ofBytes(maximumBytes));
        policy.setEmergencyFreePercent(5);
        return new RecordingStorageGuard(recordingStorageService, policy);
    }

    private RecordingStorageService.StorageSnapshot snapshot(
            long recordingBytes,
            long usableBytes
    ) {
        return new RecordingStorageService.StorageSnapshot(
                10_000,
                usableBytes,
                10_000 - usableBytes,
                recordingBytes,
                StorageHealthStatus.HEALTHY,
                20,
                10,
                Instant.parse("2026-09-12T12:00:00Z")
        );
    }
}
