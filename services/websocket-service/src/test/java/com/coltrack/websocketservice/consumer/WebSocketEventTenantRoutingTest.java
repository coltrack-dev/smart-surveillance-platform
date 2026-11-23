package com.coltrack.websocketservice.consumer;

import com.coltrack.events.websocket.CameraStatusEventWs;
import com.coltrack.events.websocket.StreamEventWs;
import com.coltrack.websocketservice.security.CameraTenantLookup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import static org.mockito.Mockito.*;

class WebSocketEventTenantRoutingTest {
    @Test
    void cameraStatusRoutesUsingPersistedOwnerAndRedactsReason() {
        UUID cameraId = UUID.randomUUID(), tenant = UUID.randomUUID();
        CameraTenantLookup lookup = mock(CameraTenantLookup.class);
        when(lookup.findAssignedTenant(cameraId)).thenReturn(Optional.of(tenant));
        SimpMessagingTemplate broker = mock(SimpMessagingTemplate.class);
        CameraStatusEventListener listener = cameraListener(broker, lookup, "oidc");
        Instant changedAt = Instant.now();

        listener.onCameraStatusEvent(new CameraStatusEventWs(cameraId, "ERROR", "rtsp://secret", changedAt));

        verify(broker).convertAndSend("/topic/tenants/" + tenant + "/cameras/status",
                new CameraStatusEventWs(cameraId, "ERROR", null, changedAt));
    }

    @Test
    void streamStatusRoutesFromPersistedOwnerAndDoesNotForwardInternalMediaDetails() {
        UUID cameraId = UUID.randomUUID(), tenant = UUID.randomUUID();
        CameraTenantLookup lookup = mock(CameraTenantLookup.class);
        when(lookup.findAssignedTenant(cameraId)).thenReturn(Optional.of(tenant));
        SimpMessagingTemplate broker = mock(SimpMessagingTemplate.class);
        StreamEventListener listener = streamListener(broker, lookup, "oidc");

        listener.onStreamEvent(new StreamEventWs(cameraId, "RUNNING", "/media-hls/internal/index.m3u8", "secret detail"));

        verify(broker).convertAndSend("/topic/tenants/" + tenant + "/streams",
                new StreamEventWs(cameraId, "RUNNING", null, null));
    }

    @Test
    void unassignedAndUnknownKafkaResourcesAreNotBroadcast() {
        UUID cameraId = UUID.randomUUID();
        CameraTenantLookup lookup = mock(CameraTenantLookup.class);
        when(lookup.findAssignedTenant(cameraId)).thenReturn(Optional.empty());
        SimpMessagingTemplate broker = mock(SimpMessagingTemplate.class);
        cameraListener(broker, lookup, "oidc").onCameraStatusEvent(
                new CameraStatusEventWs(cameraId, "ONLINE", null, Instant.now()));
        streamListener(broker, lookup, "oidc").onStreamEvent(new StreamEventWs(cameraId, "RUNNING", null, null));

        verifyNoInteractions(broker);
    }

    @Test
    void legacySurveillanceKeepsExistingGlobalDestinations() {
        UUID cameraId = UUID.randomUUID();
        CameraTenantLookup lookup = mock(CameraTenantLookup.class);
        SimpMessagingTemplate broker = mock(SimpMessagingTemplate.class);
        CameraStatusEventWs cameraEvent = new CameraStatusEventWs(cameraId, "ONLINE", null, Instant.now());
        StreamEventWs streamEvent = new StreamEventWs(cameraId, "RUNNING", "/hls/camera/index.m3u8", null);

        cameraListener(broker, lookup, "legacy").onCameraStatusEvent(cameraEvent);
        streamListener(broker, lookup, "legacy").onStreamEvent(streamEvent);

        verify(broker).convertAndSend("/topic/cameras/status", cameraEvent);
        verify(broker).convertAndSend("/topic/streams", streamEvent);
        verifyNoInteractions(lookup);
    }

    @SuppressWarnings("unchecked")
    private static CameraStatusEventListener cameraListener(SimpMessagingTemplate broker,
                                                              CameraTenantLookup lookup, String mode) {
        ObjectProvider<CameraTenantLookup> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(lookup);
        CameraStatusEventListener listener = new CameraStatusEventListener(broker, provider);
        ReflectionTestUtils.setField(listener, "securityMode", mode);
        return listener;
    }

    @SuppressWarnings("unchecked")
    private static StreamEventListener streamListener(SimpMessagingTemplate broker,
                                                       CameraTenantLookup lookup, String mode) {
        ObjectProvider<CameraTenantLookup> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(lookup);
        StreamEventListener listener = new StreamEventListener(broker, provider);
        ReflectionTestUtils.setField(listener, "securityMode", mode);
        return listener;
    }
}
