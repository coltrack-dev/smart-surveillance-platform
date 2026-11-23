package com.coltrack.streamservice.consumer;


import com.coltrack.events.CameraRegisteredEvent;
import com.coltrack.events.CameraDeletedEvent;
import com.coltrack.events.CameraUpdatedEvent;
import com.coltrack.kafka.KafkaTopics;
import com.coltrack.streamservice.service.StreamManager;
import com.coltrack.streamservice.service.BroadcastService;

import lombok.RequiredArgsConstructor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;


@Component
@RequiredArgsConstructor
@Slf4j
public class CameraEventsConsumer {

    private final StreamManager manager;
    private final BroadcastService broadcastService;

    @KafkaListener(
            topics = KafkaTopics.CAMERA_EVENTS,
            groupId = "stream-service"
    )
    public void consume(Object event) {
        log.info("Camera event received {}", event);
        if (event instanceof CameraRegisteredEvent registered) {
            if (registered.autoStart()) {
                manager.acquire(registered.cameraId(), StreamManager.CAMERA_AUTO_START_CONSUMER_ID,
                        StreamManager.CAMERA_AUTO_START_CONSUMER_TYPE);
            } else {
                log.info("Auto start disabled for camera {}", registered.cameraId());
            }
        } else if (event instanceof CameraDeletedEvent deleted) {
            broadcastService.deleteForCamera(deleted.cameraId());
            manager.releaseAll(deleted.cameraId());
        } else if (event instanceof CameraUpdatedEvent updated) {
            try {
                manager.reconcileAutoStart(updated.cameraId());
            } catch (RuntimeException error) {
                log.warn("Could not reconcile auto-start after camera update camera={}", updated.cameraId(), error);
            }
        }
    }
}
