package com.coltrack.cameraservice.service;

import com.coltrack.cameraservice.entity.CameraEntity;
import com.coltrack.cameraservice.entity.CameraStatus;
import com.coltrack.cameraservice.repository.CameraRepository;
import com.coltrack.events.websocket.CameraStatusEventWs;
import com.coltrack.events.websocket.WebSocketTopics;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Optional;
import java.util.UUID;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CameraStatusTenantEventTest {
    @Test
    void streamingStatusMutationUsesPersistedCameraOwner() {
        CameraRepository cameras = mock(CameraRepository.class);
        @SuppressWarnings("unchecked") KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        UUID cameraId = UUID.randomUUID(), tenantId = UUID.randomUUID();
        when(cameras.findById(cameraId)).thenReturn(Optional.of(CameraEntity.builder().id(cameraId).tenantId(tenantId).build()));
        when(cameras.updateStatus(eq(cameraId), eq(tenantId), eq(CameraStatus.ONLINE), isNull(), any())).thenReturn(1);
        CameraStatusService service = streaming(cameras, kafka);

        service.updateStatus(cameraId, CameraStatus.ONLINE, null);

        verify(cameras).updateStatus(eq(cameraId), eq(tenantId), eq(CameraStatus.ONLINE), isNull(), any());
        verify(cameras, never()).updateStatus(eq(cameraId), eq(CameraStatus.ONLINE), any(), any());
        verify(kafka).send(eq(WebSocketTopics.CAMERA_EVENTS), eq(cameraId.toString()), any(CameraStatusEventWs.class));
    }

    @Test
    void streamingDoesNotMutateOrPublishStatusForUnassignedCamera() {
        CameraRepository cameras = mock(CameraRepository.class);
        @SuppressWarnings("unchecked") KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        UUID cameraId = UUID.randomUUID();
        when(cameras.findById(cameraId)).thenReturn(Optional.of(CameraEntity.builder().id(cameraId).build()));

        streaming(cameras, kafka).updateStatus(cameraId, CameraStatus.ERROR, "ignored");

        verify(cameras, never()).updateStatus(any(), any(), any(), any());
        verifyNoInteractions(kafka);
    }

    private static CameraStatusService streaming(CameraRepository cameras, KafkaTemplate<String, Object> kafka) {
        CameraStatusService service = new CameraStatusService(cameras, kafka);
        ReflectionTestUtils.setField(service, "securityMode", "oidc");
        return service;
    }
}
