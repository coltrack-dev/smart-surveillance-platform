package com.coltrack.cameraservice.consumer;

import com.coltrack.cameraservice.entity.CameraStatus;
import com.coltrack.cameraservice.service.CameraStatusService;
import com.coltrack.events.*;

import com.coltrack.kafka.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;


@Component
@RequiredArgsConstructor
@Slf4j
public class StreamEventConsumer {


    private final CameraStatusService cameraStatusService;


    @KafkaListener(
            topics = KafkaTopics.STREAM_EVENTS,
            groupId = "camera-service"
    )
    public void consume(ConsumerRecord<String, Object> record) {

        Object event = record.value();
        log.info("Received stream event: {}", event);

        if (event instanceof StreamStartedEvent e) {

            cameraStatusService.updateStatus(
                    e.cameraId(),
                    CameraStatus.ONLINE,
                    null
            );


        } else if (event instanceof StreamRecoveredEvent e) {

            cameraStatusService.updateStatus(
                    e.cameraId(),
                    CameraStatus.ONLINE,
                    null
            );


        } else if (event instanceof StreamStoppedEvent e) {

            cameraStatusService.updateStatus(
                    e.cameraId(),
                    CameraStatus.OFFLINE,
                    null
            );


        } else if (event instanceof StreamFailedEvent e) {

            cameraStatusService.updateStatus(
                    e.cameraId(),
                    CameraStatus.ERROR,
                    e.reason()
            );


        } else if (event instanceof StreamReconnectingEvent e) {

            cameraStatusService.updateStatus(
                    e.cameraId(),
                    CameraStatus.ERROR,
                    "Reconnecting"
            );


        } else {

            log.warn(
                    "Unknown stream event type: {}",
                    event.getClass()
            );
        }
    }
}
