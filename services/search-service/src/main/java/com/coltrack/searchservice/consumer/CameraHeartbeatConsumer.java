package com.coltrack.searchservice.consumer;

import com.coltrack.events.CameraHeartbeatEvent;
import com.coltrack.kafka.KafkaTopics;
import com.coltrack.searchservice.service.CameraIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class CameraHeartbeatConsumer {


    private final CameraIndexService service;



    @KafkaListener(
            topics = KafkaTopics.CAMERA_HEARTBEAT,
            groupId = "search-service"
    )
    public void consume(
            CameraHeartbeatEvent event
    ) {

        log.info(
                "Received heartbeat {}",
                event
        );

        service.updateHeartbeat(event);

    }
}
