package com.coltrack.searchservice.consumer;


import com.coltrack.events.*;

import com.coltrack.kafka.KafkaTopics;
import com.coltrack.searchservice.service.CameraIndexService;

import lombok.RequiredArgsConstructor;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;


@Component
@RequiredArgsConstructor
public class CameraEventsConsumer {


    private final CameraIndexService service;


    @KafkaListener(
            topics = KafkaTopics.CAMERA_EVENTS,
            groupId = "search-service"
    )
    public void consume(
            Object event
    ) {


        if (event instanceof CameraRegisteredEvent e) {

            service.index(e);

        } else if (event instanceof CameraUpdatedEvent e) {

            service.update(e);

        } else if (event instanceof CameraDeletedEvent e) {

            service.delete(e);

        }
        else if(event instanceof CameraStatusChangedEvent e){

            service.updateStatus(e);

        }

    }

}
