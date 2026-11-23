package com.coltrack.streamservice.websocket;

import com.coltrack.events.*;
import com.coltrack.events.websocket.StreamEventWs;
import com.coltrack.events.websocket.WebSocketTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

@Component
@RequiredArgsConstructor
@Slf4j
public class StreamWebSocketPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    @Value("${app.security.mode:legacy}")
    private String securityMode;

    public void onStreamStarted(StreamStartedEvent event) {

        log.info( "onStreamStarted" );


        StreamEventWs eventWs =
                new StreamEventWs(
                        event.cameraId(),
                        "RUNNING",
                        "oidc".equalsIgnoreCase(securityMode) ? null : event.hlsUrl(),
                        null
                );

        kafkaTemplate.send(
                WebSocketTopics.STREAM_EVENTS,
                event.cameraId().toString(),
                eventWs
        );

    }

    public void onStreamStopped(StreamStoppedEvent event) {

        log.info( "onStreamStopped" );


        StreamEventWs eventWs =
                new StreamEventWs(
                        event.cameraId(),
                        "STOPPED",
                        null,
                        null
                );

        kafkaTemplate.send(
                WebSocketTopics.STREAM_EVENTS,
                event.cameraId().toString(),
                eventWs
        );
    }

    public void onStreamFailed(StreamFailedEvent  event) {

        log.info( "onStreamFailed" );

        StreamEventWs eventWs =
                new StreamEventWs(
                        event.cameraId(),
                        "FAILED",
                        null,
                        null
                );

        kafkaTemplate.send(
                WebSocketTopics.STREAM_EVENTS,
                event.cameraId().toString(),
                eventWs
        );
    }

    public void onStreamReconnecting(StreamReconnectingEvent  event) {

        log.info( "onStreamReconnecting" );

        StreamEventWs eventWs =
                new StreamEventWs(
                        event.cameraId(),
                        "RECONNECTING",
                        null,
                        null
                );

        kafkaTemplate.send(
                WebSocketTopics.STREAM_EVENTS,
                event.cameraId().toString(),
                eventWs
        );
    }
}
