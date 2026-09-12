package com.coltrack.recordingservice.service;

import com.coltrack.recordingservice.model.StorageHealthStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordingStorageServiceTest {

    @TempDir
    Path storageRoot;

    @Test
    void snapshotMeasuresFilesRecursively() throws Exception {
        Path recordingDirectory = storageRoot.resolve("camera/date/recording");
        Files.createDirectories(recordingDirectory);
        Files.write(recordingDirectory.resolve("recording-001.mkv"), new byte[128]);
        Files.write(recordingDirectory.resolve("recording-002.mkv"), new byte[256]);

        RecordingStorageService service = new RecordingStorageService(
                storageRoot.toString(),
                20,
                10
        );

        RecordingStorageService.StorageSnapshot snapshot = service.getSnapshot();

        assertEquals(384, snapshot.recordingBytes());
        assertTrue(snapshot.totalBytes() > 0);
        assertTrue(snapshot.usableBytes() >= 0);
        assertEquals(
                snapshot.totalBytes() - snapshot.usableBytes(),
                snapshot.usedBytes()
        );
        assertTrue(snapshot.checkedAt() != null);
    }

    @Test
    void rejectsOverlappingThresholds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RecordingStorageService(
                        storageRoot.toString(),
                        10,
                        10
                )
        );
    }

    @Test
    void resolvesHealthFromConfiguredThresholds() {
        RecordingStorageService service = new RecordingStorageService(
                storageRoot.toString(),
                20,
                10
        );

        assertEquals(StorageHealthStatus.HEALTHY, service.resolveStatus(20.1));
        assertEquals(StorageHealthStatus.WARNING, service.resolveStatus(20.0));
        assertEquals(StorageHealthStatus.WARNING, service.resolveStatus(10.1));
        assertEquals(StorageHealthStatus.CRITICAL, service.resolveStatus(10.0));
    }
}
