package com.coltrack.analyticsservice.service;

import com.coltrack.analyticsservice.dto.AnalyticsJobResponse;
import com.coltrack.analyticsservice.dto.RecordingAnalyticsStartRequest;
import com.coltrack.analyticsservice.entity.AnalyticsJobEntity;
import com.coltrack.analyticsservice.repository.AnalyticsJobRepository;
import com.coltrack.analyticsservice.repository.AnalyticsWorkerRepository;
import com.coltrack.events.analytics.AnalyticsJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
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

    private AnalyticsControlService service;

    @BeforeEach
    void setUp() {
        service = new AnalyticsControlService(jobRepository, workerRepository, kafkaTemplate);
        ReflectionTestUtils.setField(service, "jobsTopic", "analytics.jobs");
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
}
