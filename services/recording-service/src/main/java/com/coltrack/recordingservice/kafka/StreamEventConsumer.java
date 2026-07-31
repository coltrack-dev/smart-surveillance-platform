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
    public void consume(ConsumerRecord<String, Object> record) {

        Object event = record.value();

        log.info(
                "Stream event received {}",
                event
        );


        if (event instanceof StreamStartedEvent started) {

            log.info(
                    "Starting recording camera={}",
                    started.cameraId()
            );

            recordingManager.start(
                    started.cameraId()
            );
        }


        else if (event instanceof StreamStoppedEvent stopped) {

            log.info(
                    "Stopping recording camera={}",
                    stopped.cameraId()
            );

            recordingManager.stop(
                    stopped.cameraId()
            );
        }


        else if (event instanceof StreamFailedEvent failed) {

            log.warn(
                    "Recording failed camera={}",
                    failed.cameraId()
            );

            recordingManager.stop(
                    failed.cameraId()
            );
        }
    }
}
