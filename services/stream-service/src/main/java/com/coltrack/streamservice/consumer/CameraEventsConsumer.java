package com.coltrack.streamservice.consumer;


import com.coltrack.events.CameraRegisteredEvent;
import com.coltrack.kafka.KafkaTopics;
import com.coltrack.streamservice.service.StreamManager;

import lombok.RequiredArgsConstructor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;


@Component
@RequiredArgsConstructor
@Slf4j
public class CameraEventsConsumer {

    private final StreamManager manager;

    @KafkaListener(
            topics = KafkaTopics.CAMERA_EVENTS,
            groupId = "stream-service"
    )
    public void consume(
            CameraRegisteredEvent event
    ) {

        log.info(
                "Camera event received {}",
                event
        );

        if (!event.autoStart()) {

            log.info(
                    "Auto start disabled for camera {}",
                    event.cameraId()
            );

            return;
        }

        manager.start(
                event.cameraId()
        );
    }
}
