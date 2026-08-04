package com.coltrack.cameraservice.service;

import com.coltrack.cameraservice.entity.CameraStatus;
import com.coltrack.cameraservice.repository.CameraRepository;
import com.coltrack.events.websocket.CameraStatusEventWs;
import com.coltrack.events.websocket.WebSocketTopics;
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

    private final KafkaTemplate<String, Object> kafkaTemplate;

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

        kafkaTemplate.send(
                WebSocketTopics.CAMERA_EVENTS,
                cameraId.toString(),
                new CameraStatusEventWs(
                        cameraId,
                        status.name(),
                        reason,
                        changedAt
                )
        );
    }
}
