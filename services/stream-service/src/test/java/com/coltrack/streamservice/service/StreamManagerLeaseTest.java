package com.coltrack.streamservice.service;

import com.coltrack.streamservice.client.CameraClient;
import com.coltrack.streamservice.client.IngestAgentClient;
import com.coltrack.streamservice.client.MediaHlsClient;
import com.coltrack.streamservice.config.IngestAgentProperties;
import com.coltrack.streamservice.config.RtspStreamProperties;
import com.coltrack.streamservice.metrics.StreamMetricsService;
import com.coltrack.streamservice.model.StreamSession;
import com.coltrack.streamservice.repository.StreamConsumerLeaseRepository;
import com.coltrack.streamservice.websocket.StreamWebSocketPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StreamManagerLeaseTest {
    private StreamConsumerLeaseRepository leases;
    private StreamManager manager;
    private Map<UUID, StreamSession> sessions;
    private UUID cameraId;
    private StreamSession active;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        leases = mock(StreamConsumerLeaseRepository.class);
        manager = new StreamManager(
                mock(CameraClient.class), mock(HlsService.class), mock(KafkaTemplate.class),
                mock(StreamMetricsService.class), mock(RtspStreamProperties.class),
                mock(IngestAgentProperties.class), mock(IngestAgentClient.class),
                mock(MediaHlsClient.class), mock(StreamWebSocketPublisher.class), leases);
        Field field = StreamManager.class.getDeclaredField("sessions");
        field.setAccessible(true);
        sessions = (Map<UUID, StreamSession>) field.get(manager);
        cameraId = UUID.randomUUID();
        active = StreamSession.builder().cameraId(cameraId).workerRunning(true).build();
        sessions.put(cameraId, active);
    }

    @Test
    void releasingBroadcastLeaseDoesNotStopStreamWhileLegacyConsumerRemains() {
        when(leases.hasConsumers(cameraId)).thenReturn(true);

        manager.release(cameraId, "broadcast:" + UUID.randomUUID());

        assertFalse(active.isStopRequested());
        assertSame(active, manager.find(cameraId));
    }

    @Test
    void legacyStopReleasesOnlyLegacyLease() {
        when(leases.hasConsumers(cameraId)).thenReturn(true);

        manager.stop(cameraId);

        verify(leases).release(cameraId, StreamManager.LEGACY_CONSUMER_ID);
        assertFalse(active.isStopRequested());
    }

    @Test
    void lastConsumerReleaseRequestsExactlyOneStop() {
        when(leases.hasConsumers(cameraId)).thenReturn(false);

        manager.release(cameraId, "broadcast:" + UUID.randomUUID());
        manager.release(cameraId, "broadcast:" + UUID.randomUUID());

        assertTrue(active.isStopRequested());
        assertEquals(com.coltrack.streamservice.model.StreamStatus.STOPPING, active.getStatus());
    }

    @Test
    void concurrentAcquireCallsReuseTheSameActiveSession() throws Exception {
        int count = 24;
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = java.util.stream.IntStream.range(0, count)
                    .mapToObj(index -> executor.submit(() -> manager.acquire(cameraId, "consumer-" + index, "TEST")))
                    .toList();
            for (var task : tasks) assertSame(active, task.get());
        }
        verify(leases, times(count)).acquire(eq(cameraId), anyString(), eq("TEST"));
        verifyNoInteractions(findCameraClient());
        assertSame(active, sessions.get(cameraId));
    }

    private CameraClient findCameraClient() throws Exception {
        Field field = StreamManager.class.getDeclaredField("cameraClient");
        field.setAccessible(true);
        return (CameraClient) field.get(manager);
    }
}
