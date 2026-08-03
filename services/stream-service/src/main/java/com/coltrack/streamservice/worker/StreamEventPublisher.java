package com.coltrack.streamservice.worker;

import com.coltrack.events.StreamStartedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class StreamEventPublisher {


    private final SimpMessagingTemplate template;


    public void publish(StreamStartedEvent event) {

        log.info("publish ",  event);

        template.convertAndSend(
                "/topic/streams",
                event
        );

    }
}
