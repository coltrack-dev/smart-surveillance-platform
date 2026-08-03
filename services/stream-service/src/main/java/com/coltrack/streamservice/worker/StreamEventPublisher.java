package com.coltrack.streamservice.worker;

import com.coltrack.events.StreamStartedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StreamEventPublisher {


    private final SimpMessagingTemplate template;


    public void publish(StreamStartedEvent event) {

        template.convertAndSend(
                "/topic/streams",
                event
        );

    }
}
