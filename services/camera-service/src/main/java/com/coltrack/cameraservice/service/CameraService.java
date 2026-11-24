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
import com.coltrack.identity.quota.TenantQuotaGuard;
import com.coltrack.cameraservice.repository.CameraCreateIdempotencyRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;


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

    @Autowired(required = false)
    private TenantQuotaGuard quotaGuard;

    @Autowired(required = false)
    private CameraCreateIdempotencyRepository idempotencyRepository;

    @Transactional
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

    @Transactional
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

        return create(name, lbsLocationId, rtspUrl, rtspUsername, rtspPassword, rtspUrlFormat,
                videoProcessingMode, autoStart, tenantId, null);
    }

    @Transactional
    public CameraEntity create(
            String name, UUID lbsLocationId, String rtspUrl, String rtspUsername, String rtspPassword,
            RtspUrlFormat rtspUrlFormat, VideoProcessingMode videoProcessingMode, boolean autoStart,
            UUID tenantId, String idempotencyKey
    ) {

        if (tenantId != null && (quotaGuard == null || idempotencyRepository == null))
            throw new TenantQuotaGuard.QuotaAuthorityUnavailableException(new IllegalStateException("Streaming quota services are not configured"));
        String canonicalRequest = canonicalRequest(name, lbsLocationId, rtspUrl, rtspUsername, rtspPassword,
                rtspUrlFormat, videoProcessingMode, autoStart);
        String fingerprint;
        try {
            fingerprint = tenantId == null ? sha256(canonicalRequest) : credentialsCipher.fingerprint(canonicalRequest);
        } catch (IllegalStateException configurationError) {
            if (tenantId != null) throw new TenantQuotaGuard.QuotaAuthorityUnavailableException(configurationError);
            throw configurationError;
        }
        String keyHash = idempotencyKey == null ? null : sha256(idempotencyKey);
        if (tenantId != null && quotaGuard != null) quotaGuard.lock(tenantId);
        if (tenantId != null && idempotencyRepository != null && keyHash != null) {
            var previous = idempotencyRepository.find(tenantId, keyHash);
            if (previous.isPresent()) {
                if (!MessageDigest.isEqual(previous.get().fingerprint().getBytes(StandardCharsets.US_ASCII), fingerprint.getBytes(StandardCharsets.US_ASCII)))
                    throw new IdempotencyConflictException();
                if (previous.get().cameraId() == null) throw new IdempotencyConflictException();
                return repository.findByIdAndTenantId(previous.get().cameraId(), tenantId)
                        .orElseThrow(IdempotencyConflictException::new);
            }
        }

        if (tenantId != null && quotaGuard != null) quotaGuard.requireCapacity(tenantId, TenantQuotaGuard.Resource.CAMERA);

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
        repository.flush();
        if (tenantId != null && idempotencyRepository != null && keyHash != null)
            idempotencyRepository.save(tenantId, keyHash, fingerprint, camera.getId());

        Runnable publish = () -> kafkaTemplate.send(KafkaTopics.CAMERA_EVENTS,
                camera.getId().toString(), new CameraRegisteredEvent(camera.getId(), camera.getName(), "",
                        camera.getRtspUrl(), camera.isAutoStart(), camera.getCreatedAt(), camera.getTenantId()));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { publish.run(); }
            });
        } else publish.run();

        return camera;
    }

    private static String canonicalRequest(Object... values) {
        StringBuilder canonical = new StringBuilder();
        for (Object value : values) canonical.append(value == null ? "-" : value.toString()).append('\0');
        return canonical.toString();
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static class IdempotencyConflictException extends RuntimeException {
        public IdempotencyConflictException() { super("Idempotency key cannot be reused for this request"); }
    }


    public Page<CameraEntity> findAll(Pageable pageable) {

        return repository.findAll(pageable);
    }

    public Page<CameraEntity> findAll(UUID tenantId, Pageable pageable) {
        return repository.findAllByTenantId(tenantId, pageable);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TenantQuotaGuard.Usage usage(UUID tenantId) {
        if (quotaGuard == null) throw new TenantQuotaGuard.QuotaAuthorityUnavailableException(
                new IllegalStateException("Quota authority is not configured"));
        return quotaGuard.usage(tenantId, TenantQuotaGuard.Resource.CAMERA);
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
        if (tenantId != null && quotaGuard != null) quotaGuard.lock(tenantId);
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
