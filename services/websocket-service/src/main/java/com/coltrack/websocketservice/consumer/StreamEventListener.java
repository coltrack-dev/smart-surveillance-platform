package com.coltrack.websocketservice.consumer;

import com.coltrack.events.websocket.StreamEventWs;
import com.coltrack.events.websocket.WebSocketTopics;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import com.coltrack.websocketservice.security.CameraTenantLookup;

@Component
@RequiredArgsConstructor
public class StreamEventListener {

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectProvider<CameraTenantLookup> tenantLookup;
    @Value("${app.security.mode:legacy}")
    private String securityMode;

    @KafkaListener(
            topics = WebSocketTopics.STREAM_EVENTS,
            groupId = "websocket-service-stream"
    )
    public void onStreamEvent(StreamEventWs event) {
        if (!"oidc".equalsIgnoreCase(securityMode)) {
            messagingTemplate.convertAndSend("/topic/streams", event);
            return;
        }
        tenantLookup.getObject().findAssignedTenant(event.cameraId()).ifPresentOrElse(tenantId ->
                messagingTemplate.convertAndSend("/topic/tenants/" + tenantId + "/streams",
                        new StreamEventWs(event.cameraId(), event.status(), null, null)),
                () -> org.slf4j.LoggerFactory.getLogger(getClass()).warn(
                        "Drop stream status event for missing or unassigned cameraId={}", event.cameraId()));
    }
}
