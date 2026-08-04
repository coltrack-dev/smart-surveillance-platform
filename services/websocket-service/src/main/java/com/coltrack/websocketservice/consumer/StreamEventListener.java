package com.coltrack.websocketservice.consumer;

import com.coltrack.events.websocket.StreamEventWs;
import com.coltrack.events.websocket.WebSocketTopics;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StreamEventListener {

    private final SimpMessagingTemplate messagingTemplate;

    @KafkaListener(
            topics = WebSocketTopics.STREAM_EVENTS,
            groupId = "websocket-service-stream"
    )
    public void onStreamEvent(StreamEventWs event) {

        messagingTemplate.convertAndSend(
                "/topic/streams",
                event
        );
    }
}