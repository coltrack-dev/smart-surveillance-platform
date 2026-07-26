package com.coltrack.searchservice.consumer;

import com.coltrack.events.CameraHeartbeatEvent;
import com.coltrack.kafka.KafkaTopics;
import com.coltrack.searchservice.service.CameraIndexService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CameraHeartbeatConsumer {


    private final CameraIndexService service;



    @KafkaListener(
            topics = KafkaTopics.CAMERA_HEARTBEAT,
            groupId = "search-service"
    )
    public void consume(
            CameraHeartbeatEvent event
    ) {


        service.updateHeartbeat(event);

    }
}
