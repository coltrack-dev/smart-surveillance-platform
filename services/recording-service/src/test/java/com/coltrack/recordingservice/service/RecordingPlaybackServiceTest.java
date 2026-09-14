package com.coltrack.recordingservice.service;

import com.coltrack.recordingservice.config.RecordingPlaybackProperties;
import com.coltrack.recordingservice.model.RecordingEntity;
import com.coltrack.recordingservice.repository.RecordingObjectRepository;
import com.coltrack.recordingservice.repository.RecordingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecordingPlaybackServiceTest {
    @TempDir
    Path cacheDirectory;

    @Test
    void refusesPlaybackWhenOnlyMissingS3CatalogRowsRemain() {
        UUID recordingId = UUID.randomUUID();
        RecordingRepository recordings = mock(RecordingRepository.class);
        RecordingObjectRepository objects = mock(RecordingObjectRepository.class);
        RecordingUsageGuard usageGuard = mock(RecordingUsageGuard.class);
        RecordingUsageGuard.Lease lease = mock(RecordingUsageGuard.Lease.class);
        when(recordings.findById(recordingId)).thenReturn(Optional.of(RecordingEntity.builder()
                .id(recordingId).filePath("/missing/local/path").finishedAt(Instant.now()).build()));
        when(objects.findVerifiedActiveByRecordingId(recordingId)).thenReturn(List.of());
        when(objects.existsActiveByRecordingId(recordingId)).thenReturn(true);
        when(usageGuard.acquire(any(), any(), any())).thenReturn(lease);
        RecordingPlaybackProperties properties = new RecordingPlaybackProperties();
        properties.setCacheDirectory(cacheDirectory);
        RecordingPlaybackService service = new RecordingPlaybackService(
                recordings, objects, mock(S3StorageService.class), properties, usageGuard);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class, () -> service.prepareCombinedSource(recordingId));

        assertEquals(HttpStatus.GONE, exception.getStatusCode());
        verify(lease).close();
    }
}
