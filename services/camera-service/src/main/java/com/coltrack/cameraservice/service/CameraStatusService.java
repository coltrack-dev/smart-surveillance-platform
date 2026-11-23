package com.coltrack.cameraservice.service;

import com.coltrack.cameraservice.entity.CameraStatus;
import com.coltrack.cameraservice.entity.CameraEntity;
import com.coltrack.cameraservice.repository.CameraRepository;
import com.coltrack.events.websocket.CameraStatusEventWs;
import com.coltrack.events.websocket.WebSocketTopics;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;


@Service
@RequiredArgsConstructor
public class CameraStatusService {

    private final CameraRepository cameraRepository;

    private final KafkaTemplate<String, Object> kafkaTemplate;
    @Value("${app.security.mode:legacy}")
    private String securityMode;

    @Transactional
    public void updateStatus(
            UUID cameraId,
            CameraStatus status,
            String reason
    ) {

        CameraEntity camera = cameraRepository.findById(cameraId).orElse(null);
        if (camera == null) return;
        UUID tenantId = camera.getTenantId();
        if ("oidc".equalsIgnoreCase(securityMode) && tenantId == null) return;

        Instant changedAt = Instant.now();

        int changed = tenantId == null
                ? cameraRepository.updateStatus(cameraId, status, reason, changedAt)
                : cameraRepository.updateStatus(cameraId, tenantId, status, reason, changedAt);
        if (changed == 0) return;

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
