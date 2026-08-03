package com.coltrack.streamservice.websocket;

import com.coltrack.events.StreamStartedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StreamWebSocketListener {

    private final SimpMessagingTemplate messagingTemplate;

    @EventListener
    public void onStreamStarted(StreamStartedEvent event) {

        messagingTemplate.convertAndSend(
                "/topic/streams",
                event
        );
    }
}
