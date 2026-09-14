package com.coltrack.recordingservice.service;

import com.coltrack.recordingservice.config.S3Properties;
import com.coltrack.recordingservice.config.S3StoragePolicyProperties;
import com.coltrack.recordingservice.dto.S3ReconciliationResponse;
import com.coltrack.recordingservice.model.RecordingObjectEntity;
import com.coltrack.recordingservice.model.S3ObjectVerificationStatus;
import com.coltrack.recordingservice.repository.RecordingObjectRepository;
import com.coltrack.recordingservice.repository.S3ReconciliationIssueRepository;
import com.coltrack.recordingservice.repository.S3ReconciliationRunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3ReconciliationServiceTest {

    @Mock
    private RecordingObjectRepository recordingObjectRepository;
    @Mock
    private S3ReconciliationIssueRepository issueRepository;
    @Mock
    private S3ReconciliationRunRepository runRepository;
    @Mock
    private S3StorageService s3StorageService;
    @Mock private DistributedLockService distributedLockService;

    @Test
    void verifiesCatalogObjectsAndDetectsMissingObject() {
        when(distributedLockService.execute(anyLong(), any(), any()))
                .thenAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(2)).get());
        RecordingObjectEntity verified = object("recordings/verified.mkv", 100L);
        RecordingObjectEntity missing = object("recordings/missing.mkv", 50L);
        when(recordingObjectRepository.findAllActive())
                .thenReturn(List.of(verified, missing));
        when(s3StorageService.inspectObject(verified.getS3Key()))
                .thenReturn(Optional.of(new S3StorageService.StoredObjectMetadata(
                        verified.getS3Key(), 100L, Instant.parse("2026-01-01T00:00:00Z")
                )));
        when(s3StorageService.inspectObject(missing.getS3Key()))
                .thenReturn(Optional.empty());
        when(s3StorageService.listRecordingObjects()).thenReturn(List.of(
                new S3StorageService.StoredObjectMetadata(
                        verified.getS3Key(), 100L, Instant.parse("2026-01-01T00:00:00Z")
                )
        ));
        when(issueRepository.findByResolvedAtIsNullOrderByLastCheckedAtDesc())
                .thenReturn(List.of());
        when(recordingObjectRepository.findActiveProblems(any()))
                .thenReturn(List.of());
        when(runRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        S3ReconciliationResponse response = service().run();

        assertEquals("COMPLETED", response.status());
        assertEquals(2, response.databaseObjects());
        assertEquals(1, response.verifiedObjects());
        assertEquals(1, response.missingObjects());
        assertEquals(150, response.catalogedBytes());
        assertEquals(100, response.actualBytes());
        assertEquals(S3ObjectVerificationStatus.VERIFIED, verified.getVerificationStatus());
        assertEquals(S3ObjectVerificationStatus.MISSING, missing.getVerificationStatus());
        verify(recordingObjectRepository).save(verified);
        verify(recordingObjectRepository).save(missing);
    }

    private S3ReconciliationService service() {
        S3Properties properties = new S3Properties();
        properties.setEnabled(true);
        properties.setBucket("recordings-test");
        properties.setPrefix("recordings");
        S3StoragePolicyProperties policy = new S3StoragePolicyProperties();
        policy.setReconciliationGracePeriod(Duration.ZERO);
        return new S3ReconciliationService(
                recordingObjectRepository,
                issueRepository,
                runRepository,
                s3StorageService,
                properties,
                policy,
                distributedLockService
        );
    }

    private RecordingObjectEntity object(String key, long size) {
        return RecordingObjectEntity.builder()
                .id(UUID.randomUUID())
                .recordingId(UUID.randomUUID())
                .s3Key(key)
                .sizeBytes(size)
                .uploadedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
    }
}
