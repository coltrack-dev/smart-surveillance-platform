package com.coltrack.cameraservice.service;


import com.coltrack.cameraservice.entity.CameraEntity;
import com.coltrack.cameraservice.repository.CameraRepository;
import com.coltrack.events.CameraRegisteredEvent;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

import static com.coltrack.kafka.KafkaTopics.CAMERA_EVENTS;


@Service
public class CameraService {


    private final CameraRepository repository;

    private final KafkaTemplate<String,Object> kafkaTemplate;


    public CameraService(
            CameraRepository repository,
            KafkaTemplate<String,Object> kafkaTemplate
    ) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
    }


    public CameraEntity create(
            String name,
            String location
    ) {


        CameraEntity camera =
                new CameraEntity(
                        UUID.randomUUID(),
                        name,
                        location
                );


        repository.save(camera);


        CameraRegisteredEvent event =
                new CameraRegisteredEvent(
                        camera.getId(),
                        camera.getName(),
                        camera.getLocation(),
                        camera.getCreatedAt()
                );


        kafkaTemplate.send(
                CAMERA_EVENTS,
                camera.getId().toString(),
                event
        );


        return camera;
    }
}
