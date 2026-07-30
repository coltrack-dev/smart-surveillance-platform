package com.coltrack.cameraservice.scheduler;


import com.coltrack.cameraservice.entity.CameraEntity;
import com.coltrack.cameraservice.entity.CameraStatus;
import com.coltrack.cameraservice.repository.CameraRepository;

import com.coltrack.events.CameraStatusChangedEvent;
import com.coltrack.kafka.KafkaTopics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


import java.time.Duration;
import java.time.Instant;
import java.util.List;


@Component
@RequiredArgsConstructor
@Slf4j
public class CameraStatusScheduler {


    private static final long HEARTBEAT_TIMEOUT_SECONDS = 30;


    private final CameraRepository repository;

    private final KafkaTemplate<String, Object> kafkaTemplate;



    /**
     * Проверяем состояние камер каждые 10 секунд
     */
    //@Scheduled(fixedRate = 10000)
    public void checkStatus() {

        Instant now = Instant.now();

        List<CameraEntity> cameras =
                repository.findByStatus(
                        CameraStatus.ONLINE
                );


        for (CameraEntity camera : cameras) {

            Instant lastHeartbeat =
                    camera.getLastHeartbeat();


            if (lastHeartbeat == null) {
                continue;
            }

            Duration offlineDuration =
                    Duration.between(
                            lastHeartbeat,
                            now
                    );

            if (offlineDuration.getSeconds() > HEARTBEAT_TIMEOUT_SECONDS) {

                changeToOffline(
                        camera,
                        now
                );

            }

        }

    }



    private void changeToOffline(
            CameraEntity camera,
            Instant now
    ) {

        camera.setStatus(CameraStatus.OFFLINE);

        repository.save(camera);

        log.info(
                "Camera {} status changed ONLINE -> OFFLINE. Last heartbeat: {}",
                camera.getId(),
                camera.getLastHeartbeat()
        );


        kafkaTemplate.send(
                KafkaTopics.CAMERA_EVENTS,
                camera.getId().toString(),
                new CameraStatusChangedEvent(
                        camera.getId(),
                        CameraStatus.OFFLINE.name(),
                        now
                )
        );

    }

}
