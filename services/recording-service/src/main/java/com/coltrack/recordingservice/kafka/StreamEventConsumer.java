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
import com.coltrack.events.StreamReconnectingEvent;
import com.coltrack.events.StreamRecoveredEvent;

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

        if (event instanceof StreamReconnectingEvent reconnecting) {

            log.info(
                    "Stream reconnecting camera={}; " +
                            "recording worker manages its own reconnect",
                    reconnecting.cameraId()
            );

            return;
        }

        if (event instanceof StreamRecoveredEvent recovered) {

            log.info(
                    "Stream recovered camera={}",
                    recovered.cameraId()
            );

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
        /*
         * StreamFailedEvent является временным: после него
         * stream-service выполняет автоматический reconnect.
         * Поэтому логическую запись здесь не останавливаем.
         */
        log.warn(
                "Stream failed temporarily camera={} reason={}; " +
                        "recording worker will reconnect independently",
                event.cameraId(),
                event.reason()
        );
    }
}
