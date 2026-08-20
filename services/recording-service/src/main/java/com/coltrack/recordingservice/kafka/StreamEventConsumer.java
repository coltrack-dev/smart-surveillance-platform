package com.coltrack.recordingservice.kafka;

import com.coltrack.events.StreamFailedEvent;
import com.coltrack.events.StreamStartedEvent;
import com.coltrack.events.StreamStoppedEvent;
import com.coltrack.kafka.KafkaTopics;
import com.coltrack.recordingservice.service.RecordingManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;


@Component
@RequiredArgsConstructor
@Slf4j
public class StreamEventConsumer {


    private final RecordingManager recordingManager;


    @KafkaListener(
            topics = KafkaTopics.STREAM_EVENTS,
            groupId = "recording-service"
    )
    public void consume(
            ConsumerRecord<String, Object> record
    ) {

        Object event = record.value();


        log.info(
                "Stream event received offset={} event={}",
                record.offset(),
                event
        );


        if (event instanceof StreamStartedEvent started) {

            handleStarted(started);
            return;
        }


        if (event instanceof StreamStoppedEvent stopped) {

            handleStopped(stopped);
            return;
        }


        if (event instanceof StreamFailedEvent failed) {

            handleFailed(failed);
            return;
        }


        log.warn(
                "Unknown stream event type={}",
                event.getClass()
        );
    }


    private void handleStarted(
            StreamStartedEvent event
    ) {

        log.info(
                "Stream started event camera={}",
                event.cameraId()
        );


        recordingManager.start(
                event.cameraId(),
                event.eventId(),
                event.startedAt()
        );
    }


    private void handleStopped(
            StreamStoppedEvent event
    ) {

        log.info(
                "Stream stopped event camera={}",
                event.cameraId()
        );


        recordingManager.stop(
                event.cameraId(),
                event.stoppedAt()
        );
    }


    private void handleFailed(
            StreamFailedEvent event
    ) {

        log.warn(
                "Stream failed event camera={} reason={}",
                event.cameraId(),
                event.reason()
        );


        recordingManager.stop(
                event.cameraId(),
                event.failedAt()
        );
    }
}
