package com.coltrack.streamservice.consumer;


import com.coltrack.events.CameraRegisteredEvent;
import com.coltrack.events.CameraDeletedEvent;
import com.coltrack.events.CameraUpdatedEvent;
import com.coltrack.kafka.KafkaTopics;
import com.coltrack.streamservice.client.CameraClient;
import com.coltrack.streamservice.client.dto.CameraOwnershipDto;
import com.coltrack.streamservice.service.StreamManager;
import com.coltrack.streamservice.service.BroadcastService;

import lombok.RequiredArgsConstructor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.HttpClientErrorException;


@Component
@RequiredArgsConstructor
@Slf4j
public class CameraEventsConsumer {

    private final StreamManager manager;
    private final BroadcastService broadcastService;
    private final CameraClient cameraClient;
    @Value("${app.security.mode:legacy}")
    private String securityMode;

    @KafkaListener(
            topics = KafkaTopics.CAMERA_EVENTS,
            groupId = "stream-service"
    )
    public void consume(Object event) {
        Object cameraId = event instanceof CameraRegisteredEvent e ? e.cameraId()
                : event instanceof CameraDeletedEvent e ? e.cameraId()
                : event instanceof CameraUpdatedEvent e ? e.cameraId() : "unknown";
        log.info("Camera event received type={} cameraId={}", event.getClass().getSimpleName(), cameraId);
        if (event instanceof CameraRegisteredEvent registered) {
            if (registered.autoStart()) {
                if ("oidc".equalsIgnoreCase(securityMode)) {
                    CameraOwnershipDto ownership = resolveOwnership(registered.cameraId());
                    if (ownership == null || !"ACTIVE".equals(ownership.state()) || ownership.tenantId() == null) {
                        log.warn("Ignoring auto-start for missing or unassigned camera={}", registered.cameraId());
                        return;
                    }
                    manager.acquire(registered.cameraId(), ownership.tenantId(), StreamManager.CAMERA_AUTO_START_CONSUMER_ID,
                            StreamManager.CAMERA_AUTO_START_CONSUMER_TYPE);
                } else if (registered.tenantId() == null) {
                    manager.acquire(registered.cameraId(), StreamManager.CAMERA_AUTO_START_CONSUMER_ID,
                            StreamManager.CAMERA_AUTO_START_CONSUMER_TYPE);
                } else {
                    manager.acquire(registered.cameraId(), registered.tenantId(), StreamManager.CAMERA_AUTO_START_CONSUMER_ID,
                            StreamManager.CAMERA_AUTO_START_CONSUMER_TYPE);
                }
            } else {
                log.info("Auto start disabled for camera {}", registered.cameraId());
            }
        } else if (event instanceof CameraDeletedEvent deleted) {
            if ("oidc".equalsIgnoreCase(securityMode)) {
                CameraOwnershipDto ownership = resolveOwnership(deleted.cameraId());
                if (ownership == null || !"DELETED".equals(ownership.state()) || ownership.tenantId() == null) {
                    log.warn("Ignoring unverified camera deletion event camera={}", deleted.cameraId());
                    return;
                }
                broadcastService.deleteForCamera(deleted.cameraId(), ownership.tenantId());
                manager.releaseAll(deleted.cameraId(), ownership.tenantId());
            } else {
                broadcastService.deleteForCamera(deleted.cameraId());
                manager.releaseAll(deleted.cameraId());
            }
        } else if (event instanceof CameraUpdatedEvent updated) {
            try {
                if ("oidc".equalsIgnoreCase(securityMode)) {
                    CameraOwnershipDto ownership = resolveOwnership(updated.cameraId());
                    if (ownership == null || !"ACTIVE".equals(ownership.state()) || ownership.tenantId() == null) return;
                    manager.reconcileAutoStart(updated.cameraId(), ownership.tenantId());
                } else {
                    manager.reconcileAutoStart(updated.cameraId(), updated.tenantId());
                }
            } catch (RuntimeException error) {
                log.warn("Could not reconcile auto-start after camera update camera={}", updated.cameraId(), error);
            }
        }
    }

    private CameraOwnershipDto resolveOwnership(java.util.UUID cameraId) {
        try {
            return cameraClient.ownership(cameraId);
        } catch (HttpClientErrorException.NotFound unknownOrNoTombstone) {
            return null;
        }
    }
}
