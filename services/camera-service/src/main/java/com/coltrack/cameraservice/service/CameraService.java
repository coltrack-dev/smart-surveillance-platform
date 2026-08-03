package com.coltrack.cameraservice.service;


import com.coltrack.cameraservice.entity.CameraEntity;
import com.coltrack.cameraservice.entity.CameraStatus;
import com.coltrack.cameraservice.entity.LbsLocationEntity;
import com.coltrack.cameraservice.repository.CameraRepository;

import com.coltrack.cameraservice.repository.LbsLocationRepository;
import com.coltrack.events.CameraDeletedEvent;
import com.coltrack.events.CameraHeartbeatEvent;
import com.coltrack.events.CameraRegisteredEvent;
import com.coltrack.events.CameraUpdatedEvent;

import com.coltrack.kafka.KafkaTopics;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;


@Service
@RequiredArgsConstructor
public class CameraService {

    private final CameraRepository repository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final LbsLocationRepository lbsLocationRepository;

    public CameraEntity create(
            String name,
            UUID lbsLocationId,
            String rtspUrl,
            boolean autoStart
    ) {

        LbsLocationEntity lbsLocation = null;


        if (lbsLocationId != null) {

            lbsLocation =
                    lbsLocationRepository.findById(lbsLocationId)
                            .orElseThrow(
                                    () -> new RuntimeException(
                                            "LBS location not found"
                                    )
                            );
        }


        CameraEntity camera =
                CameraEntity.builder()
                        .id(UUID.randomUUID())
                        .cameraNumber(
                                repository.nextCameraNumber()
                        )
                        .name(name)
                        .lbsLocation(lbsLocation)
                        .rtspUrl(rtspUrl)
                        .autoStart(autoStart)
                        .status(CameraStatus.OFFLINE)
                        .createdAt(Instant.now())
                        .build();


        repository.save(camera);

        kafkaTemplate.send(
                KafkaTopics.CAMERA_EVENTS,
                camera.getId().toString(),
                new CameraRegisteredEvent(
                        camera.getId(),
                        camera.getName(),
                        "",//camera.getLocation(),
                        camera.getRtspUrl(),
                        camera.isAutoStart(),
                        camera.getCreatedAt()
                )
        );

        return camera;
    }


    public Page<CameraEntity> findAll(Pageable pageable) {

        return repository.findAll(pageable);
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
            UUID lbsLocationId,
            String rtspUrl
    ) {


        CameraEntity camera =
                findById(id);


        LbsLocationEntity lbsLocation = null;


        if (lbsLocationId != null) {

            lbsLocation =
                    lbsLocationRepository.findById(lbsLocationId)
                            .orElseThrow(
                                    () -> new RuntimeException(
                                            "LBS location not found: "
                                                    + lbsLocationId
                                    )
                            );
        }


        camera.setName(name);
        camera.setLbsLocation(lbsLocation);
        camera.setRtspUrl(rtspUrl);


        repository.save(camera);


        kafkaTemplate.send(
                KafkaTopics.CAMERA_EVENTS,
                camera.getId().toString(),
                new CameraUpdatedEvent(
                        camera.getId(),
                        camera.getName(),
                        lbsLocation != null
                                ? lbsLocation.getName()
                                : null,
                        camera.getRtspUrl(),
                        Instant.now()
                )
        );


        return camera;
    }

    public void delete(UUID id) {

        CameraEntity camera = findById(id);

        repository.delete(camera);

        kafkaTemplate.send(
                KafkaTopics.CAMERA_EVENTS,
                id.toString(),
                new CameraDeletedEvent(
                        id,
                        Instant.now()
                )
        );
    }

    public CameraEntity heartbeat(UUID id) {

        CameraEntity camera = findById(id);

        Instant now = Instant.now();

        camera.setStatus(com.coltrack.cameraservice.entity.CameraStatus.ONLINE);

        camera.setLastHeartbeat(now);

        repository.save(camera);

        kafkaTemplate.send(
                KafkaTopics.CAMERA_HEARTBEAT,
                id.toString(),
                new CameraHeartbeatEvent(
                        id,
                        now
                )
        );

        return camera;
    }
}
