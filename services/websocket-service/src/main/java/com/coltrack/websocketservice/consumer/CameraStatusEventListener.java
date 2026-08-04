package com.coltrack.websocketservice.consumer;

import com.coltrack.events.websocket.CameraStatusEventWs;
import com.coltrack.events.websocket.WebSocketTopics;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CameraStatusEventListener {

    private final SimpMessagingTemplate messagingTemplate;

    @KafkaListener(
            topics = WebSocketTopics.CAMERA_EVENTS,
            groupId = "websocket-service-camera"
    )
    public void onCameraStatusEvent(
            CameraStatusEventWs event
    ) {

        messagingTemplate.convertAndSend(
                "/topic/cameras/status",
                event
        );
    }
}
