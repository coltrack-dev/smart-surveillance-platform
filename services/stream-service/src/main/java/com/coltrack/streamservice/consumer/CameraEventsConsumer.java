package com.coltrack.streamservice.consumer;


import com.coltrack.events.CameraRegisteredEvent;
import com.coltrack.kafka.KafkaTopics;
import com.coltrack.streamservice.service.StreamService;

import lombok.RequiredArgsConstructor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;



@Component
@RequiredArgsConstructor
@Slf4j
public class CameraEventsConsumer {


    private final StreamService service;


    @KafkaListener(
            topics = "camera.events",
            groupId = "stream-service"
    )
    public void consume(
            CameraRegisteredEvent event
    ) {

        log.info(
                "Received camera event {}",
                event
        );


        service.start(
                event.cameraId()
                //,
                //event.rtspUrl()
        );

    }
}
