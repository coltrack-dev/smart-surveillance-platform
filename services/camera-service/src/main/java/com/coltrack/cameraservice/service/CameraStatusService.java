package com.coltrack.cameraservice.service;

import com.coltrack.cameraservice.entity.CameraStatus;
import com.coltrack.cameraservice.repository.CameraRepository;
import com.coltrack.events.CameraStatusChangedEvent;
import com.coltrack.kafka.KafkaTopics;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;



@Service
@RequiredArgsConstructor
public class CameraStatusService {


    private final CameraRepository cameraRepository;
    //private final KafkaTemplate<String, CameraStatusChangedEvent> kafkaTemplate;

    @Transactional
    public void updateStatus(
            UUID cameraId,
            CameraStatus status,
            String reason
    ) {

        Instant changedAt = Instant.now();


        cameraRepository.updateStatus(
                cameraId,
                status,
                reason,
                changedAt
        );


/*
        kafkaTemplate.send(
                KafkaTopics.CAMERA_EVENTS,
                cameraId.toString(),
                new CameraStatusChangedEvent(
                        cameraId,
                        CameraStatus.OFFLINE.name(),
                        changedAt
                )
        );
*/



/*
        messagingTemplate.convertAndSend(
                "/topic/cameras",
                new CameraStatusEventWs(
                        cameraId,
                        status.name(),
                        reason,
                        changedAt
                )
        );
*/

    }

}
