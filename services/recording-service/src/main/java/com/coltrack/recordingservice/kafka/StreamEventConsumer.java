package com.coltrack.recordingservice.kafka;

import com.coltrack.events.StreamFailedEvent;
import com.coltrack.events.StreamStartedEvent;
import com.coltrack.events.StreamStoppedEvent;
import com.coltrack.recordingservice.service.RecordingManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;


@Slf4j
@Component
@RequiredArgsConstructor
public class StreamEventConsumer {


    private final RecordingManager recordingManager;


    /**
     * Starts recording when stream becomes available.
     */
    @KafkaListener(
            topics = "stream-events",
            groupId = "recording-service"
    )
    public void handleStarted(
            StreamStartedEvent event
    ) {

        log.info(
                "Stream started event received camera={}",
                event.cameraId()
        );


        recordingManager.start(
                event.cameraId()
        );
    }


    /**
     * Stops recording when stream stops.
     */
    @KafkaListener(
            topics = "stream-events",
            groupId = "recording-service"
    )
    public void handleStopped(
            StreamStoppedEvent event
    ) {

        log.info(
                "Stream stopped event received camera={}",
                event.cameraId()
        );


        recordingManager.stop(
                event.cameraId()
        );
    }


    /**
     * Stops recording when stream failed.
     */
    @KafkaListener(
            topics = "stream-events",
            groupId = "recording-service"
    )
    public void handleFailed(
            StreamFailedEvent event
    ) {

        log.warn(
                "Stream failed event received camera={} reason={}",
                event.cameraId(),
                event.reason()
        );


        recordingManager.stop(
                event.cameraId()
        );
    }
}
