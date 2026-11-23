package com.coltrack.websocketservice.consumer;

import com.coltrack.events.websocket.CameraStatusEventWs;
import com.coltrack.events.websocket.WebSocketTopics;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import com.coltrack.websocketservice.security.CameraTenantLookup;

@Component
@RequiredArgsConstructor
public class CameraStatusEventListener {

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectProvider<CameraTenantLookup> tenantLookup;
    @Value("${app.security.mode:legacy}")
    private String securityMode;

    @KafkaListener(
            topics = WebSocketTopics.CAMERA_EVENTS,
            groupId = "websocket-service-camera"
    )
    public void onCameraStatusEvent(CameraStatusEventWs event) {
        if (!"oidc".equalsIgnoreCase(securityMode)) {
            messagingTemplate.convertAndSend("/topic/cameras/status", event);
            return;
        }
        tenantLookup.getObject().findAssignedTenant(event.cameraId()).ifPresentOrElse(tenantId ->
                messagingTemplate.convertAndSend("/topic/tenants/" + tenantId + "/cameras/status",
                        new CameraStatusEventWs(event.cameraId(), event.status(), null, event.changedAt())),
                () -> org.slf4j.LoggerFactory.getLogger(getClass()).warn(
                        "Drop camera status event for missing or unassigned cameraId={}", event.cameraId()));
    }
}
