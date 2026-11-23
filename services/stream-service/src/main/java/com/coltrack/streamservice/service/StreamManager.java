package com.coltrack.streamservice.service;

import com.coltrack.kafka.KafkaTopics;
import com.coltrack.streamservice.client.CameraClient;
import com.coltrack.streamservice.client.IngestAgentClient;
import com.coltrack.streamservice.client.MediaHlsClient;
import com.coltrack.streamservice.client.dto.CameraDto;
import com.coltrack.streamservice.client.dto.CameraConnectionDto;
import com.coltrack.streamservice.config.IngestAgentProperties;
import com.coltrack.streamservice.config.RtspStreamProperties;
import com.coltrack.streamservice.metrics.StreamMetricsService;
import com.coltrack.streamservice.model.StreamSession;
import com.coltrack.streamservice.model.StreamStatus;
import com.coltrack.streamservice.model.StreamConsumerLease;
import com.coltrack.streamservice.repository.StreamConsumerLeaseRepository;
import com.coltrack.streamservice.websocket.StreamWebSocketPublisher;
import com.coltrack.streamservice.worker.CameraStreamWorker;
import com.coltrack.streamservice.worker.IngestAgentStreamWorker;
import com.coltrack.streamservice.worker.StreamEventPublisher;
import com.coltrack.streamservice.worker.StreamListener;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PostConstruct;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.coltrack.events.StreamStartedEvent;
import com.coltrack.events.StreamStoppedEvent;
import com.coltrack.events.StreamFailedEvent;
import com.coltrack.events.StreamReconnectingEvent;

import java.time.Instant;

/**
 * Manages camera streams lifecycle.
 * Responsible for starting, stopping and tracking active streams.
 * Receives lifecycle callbacks from CameraStreamWorker through StreamListener.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StreamManager implements StreamListener {

    public static final String LEGACY_CONSUMER_ID = "legacy-stream-api";
    public static final String LEGACY_CONSUMER_TYPE = "LEGACY";
    public static final String CAMERA_AUTO_START_CONSUMER_ID = "camera-auto-start";
    public static final String CAMERA_AUTO_START_CONSUMER_TYPE = "CAMERA_AUTO_START";

    private final CameraClient cameraClient;
    private final HlsService hlsService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final StreamMetricsService metricsService;
    private final RtspStreamProperties rtspProperties;
    private final IngestAgentProperties ingestAgentProperties;
    private final IngestAgentClient ingestAgentClient;
    private final MediaHlsClient mediaHlsClient;
    //private final StreamEventPublisher streamWebSocketPublisher;
    private final StreamWebSocketPublisher streamWebSocketPublisher;
    private final StreamConsumerLeaseRepository leaseRepository;

    /**
     * Active camera streams.
     * Key - camera id.
     * Value - stream session.
     */
    private final Map<UUID, StreamSession> sessions = new ConcurrentHashMap<>();

    /** Legacy start/stop callers have no durable owner identity; discard their leases after a process restart. */
    @PostConstruct
    public void clearOrphanedLegacyLeases() {
        leaseRepository.releaseLegacyLeases();
    }


    /**
     * Starts camera stream.
     * If stream already running returns existing session.
     */
    public synchronized StreamSession start(UUID cameraId) {
        return acquire(cameraId, LEGACY_CONSUMER_ID, LEGACY_CONSUMER_TYPE);
    }

    public synchronized StreamSession acquire(UUID cameraId, String consumerId, String consumerType) {
        if (consumerId == null || consumerId.isBlank() || consumerId.length() > 160) {
            throw new IllegalArgumentException("consumerId must contain 1 to 160 characters");
        }
        leaseRepository.acquire(cameraId, consumerId, consumerType);
        return ensureWorker(cameraId);
    }

    private StreamSession ensureWorker(UUID cameraId) {
        StreamSession existing = sessions.get(cameraId);
        if (existing != null) {
            /*
             * FFmpeg may temporarily be absent while the existing worker is
             * waiting before a reconnect. The worker flag, rather than only
             * process.isAlive(), prevents two FFmpeg processes for one camera.
             */
            if (existing.isWorkerRunning()) {
                log.info("Stream worker already owns camera={} status={} stopRequested={}",
                        cameraId, existing.getStatus(), existing.isStopRequested());
                return existing;
            }
            log.warn("Stale stream session found camera={}, removing", cameraId);
            sessions.remove(cameraId);
        }

        CameraConnectionDto camera = cameraClient.connection(cameraId);
        StreamSession session = StreamSession.builder()
                .cameraId(camera.id())
                .rtspUrl(camera.rtspUrl())
                .videoProcessingMode(camera.videoProcessingMode())
                .status(StreamStatus.STARTING)
                .agentManaged(ingestAgentProperties.isEnabled())
                .build();
        if (session.isAgentManaged()) session.setHlsUrl(ingestAgentProperties.publicHlsUrl(cameraId));

        // Reserve the camera before the virtual thread starts. Otherwise a
        // concurrent second start can observe workerRunning=false in this gap.
        session.setWorkerRunning(true);
        sessions.put(cameraId, session);
        metricsService.registerSessionMetrics(session);

        log.info("Creating stream session camera={} processingMode={}", cameraId, camera.videoProcessingMode());
        Thread.startVirtualThread(() -> {
            try {
                if (session.isAgentManaged()) {
                    IngestAgentStreamWorker worker = new IngestAgentStreamWorker(
                            session, ingestAgentClient, mediaHlsClient, ingestAgentProperties, this);
                    log.info("Starting IngestAgentStreamWorker camera={}", cameraId);
                    worker.run();
                } else {
                    CameraStreamWorker worker = new CameraStreamWorker(session, hlsService, this, rtspProperties);
                    log.info("Starting CameraStreamWorker camera={}", cameraId);
                    worker.run();
                }
            } catch (Exception e) {
                log.error("Stream worker crashed camera={}", cameraId, e);
                failed(session);
            }
        });
        log.info("Stream worker started camera={}", cameraId);
        return session;
    }


    /**
     * Stops camera stream.
     */
    public synchronized void stop(UUID cameraId) {
        release(cameraId, LEGACY_CONSUMER_ID);
    }

    public synchronized void release(UUID cameraId, String consumerId) {
        leaseRepository.release(cameraId, consumerId);
        if (!leaseRepository.hasConsumers(cameraId)) stopWorker(cameraId);
    }

    public synchronized void releaseAll(UUID cameraId) {
        leaseRepository.releaseAll(cameraId);
        stopWorker(cameraId);
    }

    public synchronized void reconcileAutoStart(UUID cameraId) {
        CameraDto camera = cameraClient.findById(cameraId);
        if (camera.autoStart()) {
            acquire(cameraId, CAMERA_AUTO_START_CONSUMER_ID, CAMERA_AUTO_START_CONSUMER_TYPE);
        } else {
            release(cameraId, CAMERA_AUTO_START_CONSUMER_ID);
        }
    }

    private void stopWorker(UUID cameraId) {
        StreamSession session = sessions.get(cameraId);
        if (session == null || session.isStopRequested()) return;
        if (!session.isWorkerRunning()) {
            sessions.remove(cameraId, session);
            return;
        }

        log.info("Manual stop requested camera={}", cameraId);

        session.setStopRequested(true);
        session.setStatus(StreamStatus.STOPPING);

        if (!session.isAgentManaged()) {
            Process process = session.getFfmpegProcess();
            if (process != null) {
                log.info("Destroying ffmpeg process camera={}", cameraId);
                process.destroyForcibly();
            }
        }
    }

    /**
     * Returns stream session by camera id.
     */
    public StreamSession find(UUID cameraId) {
        return sessions.get(cameraId);
    }

    /**
     * Returns all active streams.
     */
    public Collection<StreamSession> findAll() {
        return sessions.values();
    }

    /**
     * Removes stream session manually.
     */
    public void remove(UUID cameraId) {
        release(cameraId, LEGACY_CONSUMER_ID);
    }

    /** Restores desired consumers and stops sessions left without any owner. */
    @Scheduled(fixedDelayString = "${STREAM_LEASE_RECONCILE_INTERVAL:5000}")
    public synchronized void reconcileLeases() {
        try {
            leaseRepository.reconcileBroadcastLeases();
            Set<UUID> desiredCameras = new HashSet<>();
            for (StreamConsumerLease lease : leaseRepository.findAll()) {
                if (CAMERA_AUTO_START_CONSUMER_TYPE.equals(lease.consumerType())) {
                    try {
                        CameraDto camera = cameraClient.findById(lease.cameraId());
                        if (!camera.autoStart()) {
                            leaseRepository.release(lease.cameraId(), CAMERA_AUTO_START_CONSUMER_ID);
                            continue;
                        }
                    } catch (org.springframework.web.client.HttpClientErrorException.NotFound deletedCamera) {
                        leaseRepository.releaseAll(lease.cameraId());
                        continue;
                    } catch (RuntimeException cameraServiceError) {
                        log.warn("Could not reconcile camera auto-start lease camera={}", lease.cameraId(), cameraServiceError);
                        desiredCameras.add(lease.cameraId());
                        continue;
                    }
                }
                desiredCameras.add(lease.cameraId());
                try {
                    ensureWorker(lease.cameraId());
                } catch (RuntimeException error) {
                    log.warn("Could not reconcile stream lease camera={} consumer={}", lease.cameraId(), lease.consumerId(), error);
                }
            }
            sessions.forEach((cameraId, session) -> {
                if (!desiredCameras.contains(cameraId) && !session.isStopRequested()) stopWorker(cameraId);
            });
        } catch (RuntimeException databaseError) {
            log.warn("Stream lease reconciliation failed; retrying later", databaseError);
        }
    }

    /**
     * Returns available cameras from camera-service.
     */
    public Collection<CameraDto> findAvailableCameras() {
        log.debug("Loading available cameras");
        return cameraClient.findAll();
    }

    /**
     * Called when FFmpeg started successfully.
     * CameraStreamWorker invokes this method.
     */
    @Override
    public void started(StreamSession session) {

        UUID cameraId = session.getCameraId();
        session.setStatus(StreamStatus.RUNNING);
        sessions.put(cameraId, session);

        log.info("Stream started successfully camera={}", cameraId);

        StreamStartedEvent event =
                new StreamStartedEvent(
                        UUID.randomUUID(),
                        cameraId,
                        session.getHlsUrl(),
                        Instant.now()
                );

        publishEvent(
                event,
                cameraId
        );

        streamWebSocketPublisher.onStreamStarted(event);
    }


    /**
     * Called when FFmpeg stopped normally.
     */
    @Override
    public void stopped(StreamSession session) {

        UUID cameraId = session.getCameraId();
        session.setStatus(StreamStatus.STOPPED);
        sessions.remove(cameraId, session);

        log.info("Stream stopped camera={}",cameraId);

        StreamStoppedEvent event =
                new StreamStoppedEvent(
                        UUID.randomUUID(),
                        cameraId,
                        Instant.now()
                );

        publishEvent(
                event,
                cameraId
        );

        streamWebSocketPublisher.onStreamStopped(event);
    }


    /**
     * Called when stream failed.
     */
    @Override
    public void failed(StreamSession session) {

        UUID cameraId = session.getCameraId();
        session.setStatus(StreamStatus.ERROR);

        /*
         * Do not remove the session here. CameraStreamWorker treats most RTSP
         * failures as recoverable and continues with RECONNECTING. The session
         * is removed only when the worker has actually stopped.
         */
        log.error(
                "Stream failed camera={}, error={}",
                cameraId,
                session.getLastError()
        );

        StreamFailedEvent event =
                new StreamFailedEvent(
                        UUID.randomUUID(),
                        cameraId,
                        session.getLastError(),
                        Instant.now()
                );

        publishEvent(
                event,
                cameraId
        );

        streamWebSocketPublisher.onStreamFailed(event);
    }


    /**
     * Called before reconnect attempt.
     */
    @Override
    public void reconnecting(StreamSession session) {

        UUID cameraId = session.getCameraId();

        session.setStatus(
                StreamStatus.RECONNECTING
        );

        log.warn(
                "Trying to reconnect stream camera={}",
                cameraId
        );

        StreamReconnectingEvent event =
                new StreamReconnectingEvent(
                        UUID.randomUUID(),
                        cameraId,
                        Instant.now()
                );

        publishEvent(
                event,
                cameraId
        );

        streamWebSocketPublisher.onStreamReconnecting(event);
    }

    /**
     * Sends stream lifecycle events to Kafka.
     */
    /**
     * Sends stream lifecycle event to Kafka.
     */
    private void publishEvent(
            Object event,
            UUID cameraId
    ) {

        log.debug(
                "Publishing stream event={} camera={}",
                event.getClass().getSimpleName(),
                cameraId
        );

        kafkaTemplate.send(
                KafkaTopics.STREAM_EVENTS,
                cameraId.toString(),
                event
        ).whenComplete(
                (result, error) -> {

                    if (error != null) {

                        log.error(
                                "Failed publishing event={} camera={}",
                                event.getClass().getSimpleName(),
                                cameraId,
                                error
                        );

                    } else {

                        log.debug(
                                "Kafka event published event={} camera={}",
                                event.getClass().getSimpleName(),
                                cameraId
                        );
                    }
                }
        );
    }

}
