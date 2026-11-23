package com.coltrack.cameraservice.service;


import com.coltrack.cameraservice.entity.CameraEntity;
import com.coltrack.cameraservice.entity.CameraStatus;
import com.coltrack.cameraservice.entity.LbsLocationEntity;
import com.coltrack.cameraservice.entity.RtspUrlFormat;
import com.coltrack.cameraservice.entity.VideoProcessingMode;
import com.coltrack.cameraservice.dto.CameraConnectionResponse;
import com.coltrack.cameraservice.repository.CameraRepository;
import com.coltrack.cameraservice.repository.CameraDeletionTombstoneRepository;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;


@Service
@RequiredArgsConstructor
public class CameraService {

    private final CameraRepository repository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final LbsLocationRepository lbsLocationRepository;
    private final CameraCredentialsCipher credentialsCipher;
    private final RtspUrlResolver rtspUrlResolver;

    @Autowired(required = false)
    private CameraDeletionTombstoneRepository deletionTombstones;

    public CameraEntity create(
            String name,
            UUID lbsLocationId,
            String rtspUrl,
            String rtspUsername,
            String rtspPassword,
            RtspUrlFormat rtspUrlFormat,
            VideoProcessingMode videoProcessingMode,
            boolean autoStart
    ) {

        return create(name, lbsLocationId, rtspUrl, rtspUsername, rtspPassword,
                rtspUrlFormat, videoProcessingMode, autoStart, null);
    }

    public CameraEntity create(
            String name,
            UUID lbsLocationId,
            String rtspUrl,
            String rtspUsername,
            String rtspPassword,
            RtspUrlFormat rtspUrlFormat,
            VideoProcessingMode videoProcessingMode,
            boolean autoStart,
            UUID tenantId
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
                        .tenantId(tenantId)
                        .cameraNumber(
                                repository.nextCameraNumber()
                        )
                        .name(name)
                        .lbsLocation(lbsLocation)
                        .rtspUrl(rtspUrl)
                        .rtspUsername(rtspUsername)
                        .rtspPasswordEncrypted(credentialsCipher.encrypt(rtspPassword))
                        .rtspUrlFormat(rtspUrlFormat == null ? RtspUrlFormat.STANDARD : rtspUrlFormat)
                        .videoProcessingMode(videoProcessingMode == null
                                ? VideoProcessingMode.AUTO : videoProcessingMode)
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
                        camera.getCreatedAt(),
                        camera.getTenantId()
                )
        );

        return camera;
    }


    public Page<CameraEntity> findAll(Pageable pageable) {

        return repository.findAll(pageable);
    }

    public Page<CameraEntity> findAll(UUID tenantId, Pageable pageable) {
        return repository.findAllByTenantId(tenantId, pageable);
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

    public CameraEntity findById(UUID id, UUID tenantId) {
        return repository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public java.util.Optional<CameraEntity> findOptionalById(UUID id) {
        return repository.findById(id);
    }

    public CameraEntity update(
            UUID id,
            String name,
            UUID lbsLocationId,
            String rtspUrl,
            String rtspUsername,
            String rtspPassword,
            RtspUrlFormat rtspUrlFormat,
            VideoProcessingMode videoProcessingMode
    ) {

        return update(id, name, lbsLocationId, rtspUrl, rtspUsername, rtspPassword,
                rtspUrlFormat, videoProcessingMode, null);
    }

    public CameraEntity update(
            UUID id,
            String name,
            UUID lbsLocationId,
            String rtspUrl,
            String rtspUsername,
            String rtspPassword,
            RtspUrlFormat rtspUrlFormat,
            VideoProcessingMode videoProcessingMode,
            UUID tenantId
    ) {


        CameraEntity camera =
                tenantId == null ? findById(id) : findById(id, tenantId);


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
        camera.setRtspUsername(rtspUsername);
        if (rtspPassword != null && !rtspPassword.isBlank()) {
            camera.setRtspPasswordEncrypted(credentialsCipher.encrypt(rtspPassword));
        }
        camera.setRtspUrlFormat(rtspUrlFormat == null
                ? RtspUrlFormat.STANDARD : rtspUrlFormat);
        camera.setVideoProcessingMode(videoProcessingMode == null
                ? VideoProcessingMode.AUTO : videoProcessingMode);


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
                        Instant.now(),
                        camera.getTenantId()
                )
        );


        return camera;
    }

    public CameraConnectionResponse connection(UUID id) {
        CameraEntity camera = findById(id);
        return new CameraConnectionResponse(
                camera.getId(),
                rtspUrlResolver.resolve(camera),
                camera.getVideoProcessingMode()
        );
    }

    public CameraConnectionResponse connection(UUID id, UUID tenantId) {
        CameraEntity camera = findById(id, tenantId);
        return new CameraConnectionResponse(camera.getId(), rtspUrlResolver.resolve(camera),
                camera.getVideoProcessingMode());
    }

    @Transactional
    public void delete(UUID id) {
        deleteAndPublish(findById(id));
    }

    @Transactional
    public void delete(UUID id, UUID tenantId) {
        deleteAndPublish(findById(id, tenantId));
    }

    private void deleteAndPublish(CameraEntity camera) {
        Instant deletedAt = Instant.now();
        if (deletionTombstones != null) deletionTombstones.save(camera.getId(), camera.getTenantId(), deletedAt);
        else if (camera.getTenantId() != null) {
            throw new IllegalStateException("Camera deletion tombstone store is required for tenant-owned Cameras");
        }
        repository.delete(camera);
        Runnable publish = () -> kafkaTemplate.send(KafkaTopics.CAMERA_EVENTS, camera.getId().toString(),
                new CameraDeletedEvent(camera.getId(), deletedAt, camera.getTenantId()));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { publish.run(); }
            });
        } else {
            publish.run();
        }
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

    public CameraEntity heartbeat(UUID id, UUID tenantId) {
        CameraEntity camera = findById(id, tenantId);
        Instant now = Instant.now();
        camera.setStatus(CameraStatus.ONLINE);
        camera.setLastHeartbeat(now);
        repository.save(camera);
        kafkaTemplate.send(KafkaTopics.CAMERA_HEARTBEAT, id.toString(), new CameraHeartbeatEvent(id, now));
        return camera;
    }
}
