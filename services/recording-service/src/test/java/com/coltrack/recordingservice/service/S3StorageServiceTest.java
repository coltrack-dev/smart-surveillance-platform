package com.coltrack.recordingservice.service;

import com.coltrack.recordingservice.config.S3Properties;
import com.coltrack.recordingservice.config.S3StoragePolicyProperties;
import com.coltrack.recordingservice.model.RecordingObjectEntity;
import com.coltrack.recordingservice.model.RecordingSession;
import com.coltrack.recordingservice.repository.RecordingObjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ObjectVersion;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

class S3StorageServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void skipsPutWhenCatalogAndRemoteObjectAlreadyMatch() throws Exception {
        S3Client client = mock(S3Client.class);
        RecordingObjectRepository repository = mock(RecordingObjectRepository.class);
        RecordingUsageGuard usageGuard = mock(RecordingUsageGuard.class);
        DistributedLockService lockService = mock(DistributedLockService.class);
        RecordingUsageGuard.Lease lease = mock(RecordingUsageGuard.Lease.class);
        when(usageGuard.acquire(any(), any(), any())).thenReturn(lease);
        when(lockService.executeBlocking(anyLong(), any(), any())).thenAnswer(
                invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(2)).get());

        UUID cameraId = UUID.randomUUID();
        UUID recordingId = UUID.randomUUID();
        Path segment = temporaryDirectory.resolve("recording-000.mkv");
        Files.write(segment, new byte[]{1, 2, 3});
        String key = "recordings/" + cameraId + "/2026-09-14/" + recordingId + "/recording-000.mkv";
        RecordingObjectEntity existing = RecordingObjectEntity.builder()
                .id(UUID.randomUUID()).recordingId(recordingId).s3Key(key).sizeBytes(3L).build();
        when(repository.findActiveByS3Key(key)).thenReturn(Optional.of(existing));
        when(repository.sumActiveSizeBytes()).thenReturn(3L);
        when(client.headObject(any(software.amazon.awssdk.services.s3.model.HeadObjectRequest.class)))
                .thenReturn(software.amazon.awssdk.services.s3.model.HeadObjectResponse.builder()
                        .contentLength(3L).lastModified(Instant.now()).build());

        S3Properties properties = properties();
        S3StorageService service = new S3StorageService(
                client, properties, new S3StoragePolicyProperties(), repository, usageGuard, lockService);
        service.uploadRecording(RecordingSession.builder()
                .id(recordingId).cameraId(cameraId).startedAt(Instant.parse("2026-09-14T00:00:00Z"))
                .filePath(temporaryDirectory.toString()).build());

        verify(client, never()).putObject(
                any(software.amazon.awssdk.services.s3.model.PutObjectRequest.class),
                any(software.amazon.awssdk.core.sync.RequestBody.class));
        verify(repository).save(existing);
        verify(lease).close();
    }

    @Test
    void deletesEveryVersionAndDeleteMarker() {
        S3Client client = mock(S3Client.class);
        when(client.listObjectVersions(any(software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest.class)))
                .thenReturn(ListObjectVersionsResponse.builder()
                        .versions(List.of(
                                ObjectVersion.builder().key("recordings/a.mkv").versionId("v1").size(10L).build(),
                                ObjectVersion.builder().key("recordings/a.mkv").versionId("v2").size(20L).build()))
                        .deleteMarkers(DeleteMarkerEntry.builder()
                                .key("recordings/a.mkv").versionId("marker").build())
                        .isTruncated(false).build());
        S3Properties properties = properties();
        S3StorageService service = new S3StorageService(
                client, properties, new S3StoragePolicyProperties(),
                mock(RecordingObjectRepository.class), mock(RecordingUsageGuard.class),
                mock(DistributedLockService.class));

        assertEquals(30L, service.deleteObjectCompletely("recordings/a.mkv"));
        verify(client, org.mockito.Mockito.times(3)).deleteObject(
                any(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class));
    }

    private S3Properties properties() {
        S3Properties properties = new S3Properties();
        properties.setEnabled(true);
        properties.setBucket("bucket");
        properties.setPrefix("recordings");
        return properties;
    }
}
