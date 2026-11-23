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
        Object cameraId = event instanceof StreamStartedEvent e ? e.cameraId()
                : event instanceof StreamStoppedEvent e ? e.cameraId()
                : event instanceof StreamFailedEvent e ? e.cameraId()
                : event instanceof StreamReconnectingEvent e ? e.cameraId()
                : event instanceof StreamRecoveredEvent e ? e.cameraId() : "unknown";
        log.info("Stream event received offset={} type={} cameraId={}", record.offset(),
                event.getClass().getSimpleName(), cameraId);


        if (event instanceof StreamStartedEvent started) {

            log.info(
                    "Stream started camera={}; recording remains manual",
                    started.cameraId()
            );
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
                "Stream failed temporarily camera={}; " +
                        "recording worker will reconnect independently",
                event.cameraId()
        );
    }
}
