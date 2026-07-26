package com.coltrack.cameraservice.service;


import com.coltrack.cameraservice.entity.CameraEntity;
import com.coltrack.cameraservice.repository.CameraRepository;
import com.coltrack.events.CameraDeletedEvent;
import com.coltrack.events.CameraRegisteredEvent;
import com.coltrack.events.CameraUpdatedEvent;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;


import static com.coltrack.kafka.KafkaTopics.CAMERA_EVENTS;


@Service
public class CameraService {


    private final CameraRepository repository;

    private final KafkaTemplate<String, Object> kafkaTemplate;


    public CameraService(
            CameraRepository repository,
            KafkaTemplate<String, Object> kafkaTemplate
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


        kafkaTemplate.send(
                CAMERA_EVENTS,
                camera.getId().toString(),
                new CameraRegisteredEvent(
                        camera.getId(),
                        camera.getName(),
                        camera.getLocation(),
                        camera.getCreatedAt()
                )
        );


        return camera;
    }


    public List<CameraEntity> findAll() {

        return repository.findAll();

    }


    public CameraEntity findById(
            UUID id
    ) {

        return repository.findById(id)
                .orElseThrow(
                        () -> new RuntimeException(
                                "Camera not found: " + id
                        )
                );

    }


    public CameraEntity update(
            UUID id,
            String name,
            String location
    ) {


        CameraEntity camera =
                findById(id);


        camera.setName(name);

        camera.setLocation(location);


        repository.save(camera);


        kafkaTemplate.send(
                CAMERA_EVENTS,
                camera.getId().toString(),
                new CameraUpdatedEvent(
                        camera.getId(),
                        camera.getName(),
                        camera.getLocation()
                )
        );


        return camera;

    }


    public void delete(
            UUID id
    ) {


        CameraEntity camera =
                findById(id);


        repository.delete(camera);


        kafkaTemplate.send(
                CAMERA_EVENTS,
                id.toString(),
                new CameraDeletedEvent(id)
        );

    }

}
