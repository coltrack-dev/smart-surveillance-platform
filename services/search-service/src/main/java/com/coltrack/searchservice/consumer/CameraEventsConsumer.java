package com.coltrack.searchservice.consumer;

import com.coltrack.events.CameraRegisteredEvent;


import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CameraEventsConsumer {

    @KafkaListener(
            topics = "camera.events",
            groupId = "search-service"
    )
    public void consume(CameraRegisteredEvent event) {

        log.info(
                "Camera received from Kafka: {}",
                event
        );

    }

}
