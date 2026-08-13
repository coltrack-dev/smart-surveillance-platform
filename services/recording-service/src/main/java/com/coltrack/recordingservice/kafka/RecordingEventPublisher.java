package com.coltrack.recordingservice.kafka;

import com.coltrack.events.RecordingReadyEvent;
import com.coltrack.kafka.KafkaTopics;
import com.coltrack.recordingservice.model.RecordingSession;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class RecordingEventPublisher {

    private final KafkaTemplate<String, Object>
            kafkaTemplate;

    public void publishReady(
            RecordingSession session
    ) {

        RecordingReadyEvent event =
                new RecordingReadyEvent(
                        UUID.randomUUID(),
                        1,
                        "RECORDING_READY",
                        session.getCameraId(),
                        session.getId(),
                        session.getStartedAt(),
                        session.getFinishedAt(),
                        session.getDurationSeconds(),
                        Instant.now()
                );

        kafkaTemplate.send(
                        KafkaTopics.RECORDING_EVENTS,
                        session.getId().toString(),
                        event
                )
                .whenComplete(
                        (result, error) -> {

                            if (error != null) {

                                log.error(
                                        "RecordingReadyEvent publish failed recordingId={}",
                                        session.getId(),
                                        error
                                );

                                return;
                            }

                            log.info(
                                    "RecordingReadyEvent published recordingId={}, cameraId={}",
                                    session.getId(),
                                    session.getCameraId()
                            );
                        }
                );
    }
}
