package com.coltrack.streamservice.websocket;

import com.coltrack.events.StreamEvent;
import com.coltrack.events.StreamStartedEvent;
import com.coltrack.events.StreamFailedEvent;
import com.coltrack.events.StreamStoppedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class StreamWebSocketPublisher {

    private final SimpMessagingTemplate messagingTemplate;

    //@EventListener
    public void onStreamStarted(StreamStartedEvent event) {

        log.info( "onStreamStarted" );

        messagingTemplate.convertAndSend(
                "/topic/streams",
                new StreamEvent(
                        event.cameraId(),
                        "ONLINE",
                        event.hlsUrl(),
                        null
                )
        );
    }

    //@EventListener
    public void onStreamStopped(StreamStoppedEvent event) {

        log.info( "onStreamStopped" );

        messagingTemplate.convertAndSend(
                "/topic/streams",
                new StreamEvent(
                        event.cameraId(),
                        "OFFLINE",
                        null,
                        null
                )
        );
    }

    //@EventListener
    public void onStreamFailed(StreamFailedEvent  event) {

        log.info( "onStreamFailed" );

        messagingTemplate.convertAndSend(
                "/topic/streams",
                new StreamEvent(
                        event.cameraId(),
                        "OFFLINE",
                        null,
                        event.reason()
                )
        );
    }

}
