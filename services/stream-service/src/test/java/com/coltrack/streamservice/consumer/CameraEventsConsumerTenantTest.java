package com.coltrack.streamservice.consumer;

import com.coltrack.events.CameraDeletedEvent;
import com.coltrack.streamservice.service.BroadcastService;
import com.coltrack.streamservice.service.StreamManager;
import com.coltrack.streamservice.client.CameraClient;
import com.coltrack.streamservice.client.dto.CameraOwnershipDto;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import java.util.UUID;
import static org.mockito.Mockito.*;

class CameraEventsConsumerTenantTest {
    @Test
    void streamingIgnoresUnscopedCameraDeletionEvents() {
        StreamManager streams = mock(StreamManager.class);
        BroadcastService broadcasts = mock(BroadcastService.class);
        CameraClient cameras = mock(CameraClient.class);
        CameraEventsConsumer consumer = streamingConsumer(streams, broadcasts, cameras);
        UUID cameraId = UUID.randomUUID();

        consumer.consume(new CameraDeletedEvent(cameraId, Instant.now(), UUID.randomUUID()));

        verify(cameras).ownership(cameraId);
        verifyNoInteractions(streams, broadcasts);
    }

    @Test
    void streamingScopesCameraDeletionCleanupByTenant() {
        StreamManager streams = mock(StreamManager.class);
        BroadcastService broadcasts = mock(BroadcastService.class);
        CameraClient cameras = mock(CameraClient.class);
        CameraEventsConsumer consumer = streamingConsumer(streams, broadcasts, cameras);
        UUID cameraId = UUID.randomUUID(), tenantId = UUID.randomUUID(), forgedTenant = UUID.randomUUID();
        when(cameras.ownership(cameraId)).thenReturn(new CameraOwnershipDto(cameraId, tenantId, "DELETED"));

        consumer.consume(new CameraDeletedEvent(cameraId, Instant.now(), forgedTenant));

        verify(broadcasts).deleteForCamera(cameraId, tenantId);
        verify(streams).releaseAll(cameraId, tenantId);
        verify(cameras).ownership(cameraId);
        verifyNoMoreInteractions(streams, broadcasts, cameras);
    }

    private static CameraEventsConsumer streamingConsumer(StreamManager streams, BroadcastService broadcasts,
                                                           CameraClient cameras) {
        CameraEventsConsumer consumer = new CameraEventsConsumer(streams, broadcasts, cameras);
        ReflectionTestUtils.setField(consumer, "securityMode", "oidc");
        return consumer;
    }
}
