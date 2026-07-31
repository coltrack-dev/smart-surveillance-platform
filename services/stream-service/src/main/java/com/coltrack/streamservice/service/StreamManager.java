package com.coltrack.streamservice.service;

import com.coltrack.kafka.KafkaTopics;
import com.coltrack.streamservice.client.CameraClient;
import com.coltrack.streamservice.client.dto.CameraDto;
import com.coltrack.streamservice.metrics.StreamMetricsService;
import com.coltrack.streamservice.model.StreamSession;
import com.coltrack.streamservice.model.StreamStatus;
import com.coltrack.streamservice.worker.CameraStreamWorker;
import com.coltrack.streamservice.worker.StreamListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;
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

    private final CameraClient cameraClient;
    private final HlsService hlsService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final StreamMetricsService metricsService;

    /**
     * Active camera streams.
     * Key - camera id.
     * Value - stream session.
     */
    private final Map<UUID, StreamSession> sessions = new ConcurrentHashMap<>();


    /**
     * Starts camera stream.
     * If stream already running returns existing session.
     */
    public StreamSession start(UUID cameraId) {

        StreamSession existing = sessions.get(cameraId);


        if (existing != null) {

            Process process = existing.getFfmpegProcess();

            if (process != null && process.isAlive()) {

                log.info("Stream already running camera={}", cameraId);
                return existing;
            }

            log.warn("Stale stream session found camera={}, removing", cameraId);

            sessions.remove(cameraId);
        }

        CameraDto camera = cameraClient.findById(cameraId);
        StreamSession session = StreamSession.builder()
                .cameraId(camera.id())
                .rtspUrl(camera.rtspUrl())
                .status(StreamStatus.STARTING)
                .build();
        sessions.put(cameraId, session);

        metricsService.registerSessionMetrics(
                session
        );

        log.info("Creating stream session camera={} rtsp={}", cameraId, camera.rtspUrl());
        Thread.startVirtualThread(() -> {
            try {
                CameraStreamWorker worker = new CameraStreamWorker(
                        session,
                        hlsService,
                        this
                );
                log.info("Starting CameraStreamWorker camera={}", cameraId);
                worker.run();
            } catch (Exception e) {
                log.error("CameraStreamWorker crashed camera={}", cameraId, e);
                failed(session);
            }
        });
        log.info("Stream worker started camera={}", cameraId);
        return session;
    }


    /**
     * Stops camera stream.
     */
    public void stop(UUID cameraId) {

        StreamSession session = sessions.get(cameraId);
        if (session == null) {
            log.warn("Stream not found camera={}", cameraId);
            return;
        }

        log.info("Manual stop requested camera={}", cameraId);

        session.setStopRequested(true);
        session.setStatus(StreamStatus.STOPPING);

        Process process = session.getFfmpegProcess();
        if (process != null) {
            log.info("Destroying ffmpeg process camera={}", cameraId);
            process.destroyForcibly();
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
        log.info("Removing stream session camera={}", cameraId);
        sessions.remove(cameraId);
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
/*
        publishEvent(
                "STREAM_STARTED",
                session
        );
*/
        publishEvent(
                new StreamStartedEvent(
                        UUID.randomUUID(),
                        session.getCameraId(),
                        Instant.now()
                ),
                session.getCameraId()
        );
    }

    /**
     * Called when FFmpeg stopped normally.
     */
    @Override
    public void stopped(StreamSession session) {
        UUID cameraId = session.getCameraId();
        session.setStatus(StreamStatus.STOPPED);
        sessions.remove(cameraId);
        log.info("Stream stopped camera={}", cameraId);
/*
        publishEvent(
                "STREAM_STOPPED",
                session
        );
*/
        publishEvent(
                new StreamStoppedEvent(
                        UUID.randomUUID(),
                        session.getCameraId(),
                        Instant.now()
                ),
                session.getCameraId()
        );
    }

    /**
     * Called when stream failed.
     */
    @Override
    public void failed(StreamSession session) {
        UUID cameraId = session.getCameraId();
        session.setStatus(StreamStatus.ERROR);
        sessions.remove(cameraId);
        log.error("Stream failed camera={}", cameraId);
/*
        publishEvent(
                "STREAM_FAILED",
                session
        );
*/
        publishEvent(
                new StreamFailedEvent(
                        UUID.randomUUID(),
                        session.getCameraId(),
                        session.getLastError(),
                        Instant.now()
                ),
                session.getCameraId()
        );
    }

    /**
     * Called before reconnect attempt.
     */
    @Override
    public void reconnecting(StreamSession session) {
        UUID cameraId = session.getCameraId();
        session.setStatus(StreamStatus.RECONNECTING);
        log.warn("Trying to reconnect stream camera={}", cameraId);
/*
        publishEvent(
                "STREAM_RECONNECTING",
                session
        );
*/
        publishEvent(
                new StreamReconnectingEvent(
                        UUID.randomUUID(),
                        session.getCameraId(),
                        Instant.now()
                ),
                session.getCameraId()
        );
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

/*
    */
/**
     * Kafka event DTO.
     *//*

    public record StreamEvent(
            String type,
            UUID cameraId,
            StreamStatus status,
            long timestamp
    ) {
    }
*/
}
