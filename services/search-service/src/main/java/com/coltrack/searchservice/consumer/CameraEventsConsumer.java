package com.coltrack.searchservice.consumer;

import com.coltrack.events.CameraRegisteredEvent;
import com.coltrack.kafka.KafkaTopics;
import com.coltrack.searchservice.service.CameraIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CameraEventsConsumer {

    private final CameraIndexService cameraIndexService;

    @KafkaListener(
            topics = KafkaTopics.CAMERA_EVENTS,
            groupId = "search-service"
    )
    public void consume(CameraRegisteredEvent event) {

        log.info("Received event {}", event);

        cameraIndexService.index(event);

        // проверка DLT
        //throw new RuntimeException(
        //        "TEST DLT"
        //);
    }

}
