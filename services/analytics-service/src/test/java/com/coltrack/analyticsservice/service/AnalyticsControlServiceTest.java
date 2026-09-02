package com.coltrack.analyticsservice.service;

import com.coltrack.analyticsservice.client.CameraConnectionClient;
import com.coltrack.analyticsservice.dto.AnalyticsJobResponse;
import com.coltrack.analyticsservice.dto.RealtimeAnalyticsStartRequest;
import com.coltrack.analyticsservice.dto.RecordingAnalyticsStartRequest;
import com.coltrack.analyticsservice.entity.AnalyticsJobEntity;
import com.coltrack.analyticsservice.repository.AnalyticsJobRepository;
import com.coltrack.analyticsservice.repository.AnalyticsWorkerRepository;
import com.coltrack.events.analytics.AnalyticsJob;
import com.coltrack.events.analytics.AnalyticsJobStatusEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
class AnalyticsControlServiceTest {

    @Mock
    private AnalyticsJobRepository jobRepository;

    @Mock
    private AnalyticsWorkerRepository workerRepository;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Mock
    private CameraConnectionClient cameraConnectionClient;

    private AnalyticsControlService service;

    @BeforeEach
    void setUp() {
        service = new AnalyticsControlService(
                jobRepository, workerRepository, kafkaTemplate, cameraConnectionClient
        );
        ReflectionTestUtils.setField(service, "jobsTopic", "analytics.jobs");
    }

    @Test
    void shouldResolveRealtimeSourceFromCameraServiceWhenOverrideIsEmpty() {
        UUID cameraId = UUID.randomUUID();
        String resolvedUrl = "rtsp://nvr.lan:554/channel-8";
        when(cameraConnectionClient.connection(cameraId)).thenReturn(
                new CameraConnectionClient.CameraConnection(cameraId, resolvedUrl, "AUTO")
        );
        when(jobRepository
                .findFirstByCameraIdAndJobTypeAndStatusInOrderByCreatedAtDesc(
                        any(), anyString(), any()
                ))
                .thenReturn(Optional.empty());
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        RealtimeAnalyticsStartRequest request = new RealtimeAnalyticsStartRequest(
                null, "tcp", null, List.of(0), new BigDecimal("0.5"),
                "auto", new BigDecimal("0.5"), List.of(), BigDecimal.TEN, Map.of()
        );

        AnalyticsJobResponse response = service.startRealtime(cameraId, request);

        assertThat(response.sourceUrl()).isEqualTo(resolvedUrl);
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(anyString(), anyString(), eventCaptor.capture());
        AnalyticsJob event = (AnalyticsJob) eventCaptor.getValue();
        assertThat(event.source().url()).isEqualTo(resolvedUrl);
    }

    @Test
    void shouldCreateRecordingJobBeforePublishingIt() {
        UUID recordingId = UUID.randomUUID();
        UUID cameraId = UUID.randomUUID();
        RecordingAnalyticsStartRequest request = new RecordingAnalyticsStartRequest(
                cameraId,
                null,
                List.of(0),
                new BigDecimal("0.5"),
                "auto",
                new BigDecimal("0.5"),
                List.of(),
                BigDecimal.TEN,
                Map.of()
        );
        when(jobRepository
                .findFirstByRecordingIdAndJobTypeAndStatusInOrderByCreatedAtDesc(
                        any(), anyString(), any()
                ))
                .thenReturn(Optional.empty());
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        AnalyticsJobResponse response = service.startRecording(recordingId, request);

        ArgumentCaptor<AnalyticsJobEntity> entityCaptor =
                ArgumentCaptor.forClass(AnalyticsJobEntity.class);
        verify(jobRepository).saveAndFlush(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getRecordingId()).isEqualTo(recordingId);
        assertThat(entityCaptor.getValue().getStatus()).isEqualTo("REQUESTED");

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        InOrder publishOrder = inOrder(jobRepository, kafkaTemplate);
        publishOrder.verify(jobRepository).saveAndFlush(any());
        publishOrder.verify(kafkaTemplate).send(
                org.mockito.ArgumentMatchers.eq("analytics.jobs"),
                org.mockito.ArgumentMatchers.eq(cameraId.toString()),
                eventCaptor.capture()
        );
        AnalyticsJob event = (AnalyticsJob) eventCaptor.getValue();
        assertThat(event.recordingId()).isEqualTo(recordingId);
        assertThat(event.jobType()).isEqualTo("RECORDING");
        assertThat(event.profile().lines()).hasSize(1);
        assertThat(event.profile().lines().getFirst().id()).isEqualTo("main-line");
        assertThat(response.recordingId()).isEqualTo(recordingId);
    }

    @Test
    void shouldIgnoreRunningStatusAfterCompletion() {
        AnalyticsJobEntity entity = jobEntity("COMPLETED");
        entity.setFinishedAt(OffsetDateTime.now(ZoneOffset.UTC));
        when(jobRepository.findByIdForUpdate(entity.getJobId()))
                .thenReturn(Optional.of(entity));

        service.applyStatus(statusEvent(entity, "RUNNING"));

        assertThat(entity.getStatus()).isEqualTo("COMPLETED");
        verify(jobRepository, never()).save(entity);
    }

    @Test
    void shouldIgnoreCompletedWhileStopIsRequested() {
        AnalyticsJobEntity entity = jobEntity("STOP_REQUESTED");
        when(jobRepository.findByIdForUpdate(entity.getJobId()))
                .thenReturn(Optional.of(entity));

        service.applyStatus(statusEvent(entity, "COMPLETED"));

        assertThat(entity.getStatus()).isEqualTo("STOP_REQUESTED");
        assertThat(entity.getFinishedAt()).isNull();
        verify(jobRepository, never()).save(entity);
    }

    @Test
    void shouldCompleteRequestedStopWithStoppedStatus() {
        AnalyticsJobEntity entity = jobEntity("STOP_REQUESTED");
        when(jobRepository.findByIdForUpdate(entity.getJobId()))
                .thenReturn(Optional.of(entity));

        service.applyStatus(statusEvent(entity, "STOPPED"));

        assertThat(entity.getStatus()).isEqualTo("STOPPED");
        assertThat(entity.getFinishedAt()).isNotNull();
        verify(jobRepository).save(entity);
    }

    @Test
    void shouldAllowProgressUpdatesWhileRunning() {
        AnalyticsJobEntity entity = jobEntity("RUNNING");
        when(jobRepository.findByIdForUpdate(entity.getJobId()))
                .thenReturn(Optional.of(entity));
        AnalyticsJobStatusEvent event = statusEvent(
                entity,
                "RUNNING",
                Map.of("progressPercent", 42)
        );

        service.applyStatus(event);

        assertThat(entity.getStatus()).isEqualTo("RUNNING");
        assertThat(entity.getDetails()).containsEntry("progressPercent", 42);
        verify(jobRepository).save(entity);
    }

    @Test
    void realtimeStopMustTargetTheRunningJobId() {
        AnalyticsJobEntity entity = jobEntity("RUNNING");
        entity.setJobType("REALTIME");
        entity.setRecordingId(null);
        when(jobRepository.findActiveCameraJobForUpdate(
                any(), anyString(), any()
        )).thenReturn(Optional.of(entity));
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        service.stopRealtime(entity.getCameraId());

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(
                org.mockito.ArgumentMatchers.eq("analytics.jobs"),
                org.mockito.ArgumentMatchers.eq(entity.getCameraId().toString()),
                eventCaptor.capture()
        );
        AnalyticsJob stop = (AnalyticsJob) eventCaptor.getValue();
        assertThat(stop.jobId()).isEqualTo(entity.getJobId());
        assertThat(stop.action()).isEqualTo("STOP");
    }

    @Test
    void shouldReturnConflictWhenDatabaseRejectsSecondActiveJob() {
        UUID recordingId = UUID.randomUUID();
        UUID cameraId = UUID.randomUUID();
        RecordingAnalyticsStartRequest request = new RecordingAnalyticsStartRequest(
                cameraId,
                null,
                List.of(0),
                new BigDecimal("0.5"),
                "auto",
                new BigDecimal("0.5"),
                List.of(),
                BigDecimal.TEN,
                Map.of()
        );
        when(jobRepository
                .findFirstByRecordingIdAndJobTypeAndStatusInOrderByCreatedAtDesc(
                        any(), anyString(), any()
                ))
                .thenReturn(Optional.empty());
        when(jobRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate active job"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.startRecording(recordingId, request)
        )
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(
                        ((ResponseStatusException) exception).getStatusCode().value()
                ).isEqualTo(409));

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    private AnalyticsJobEntity jobEntity(String status) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return AnalyticsJobEntity.builder()
                .jobId(UUID.randomUUID())
                .cameraId(UUID.randomUUID())
                .recordingId(UUID.randomUUID())
                .jobType("RECORDING")
                .status(status)
                .profile(Map.of())
                .details(Map.of())
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private AnalyticsJobStatusEvent statusEvent(
            AnalyticsJobEntity entity,
            String status
    ) {
        return statusEvent(entity, status, Map.of());
    }

    private AnalyticsJobStatusEvent statusEvent(
            AnalyticsJobEntity entity,
            String status,
            Map<String, Object> details
    ) {
        return new AnalyticsJobStatusEvent(
                UUID.randomUUID(),
                1,
                "ANALYTICS_JOB_STATUS",
                entity.getJobId(),
                entity.getJobType(),
                "worker-1",
                entity.getCameraId(),
                entity.getRecordingId(),
                status,
                OffsetDateTime.now(ZoneOffset.UTC),
                details
        );
    }
}
