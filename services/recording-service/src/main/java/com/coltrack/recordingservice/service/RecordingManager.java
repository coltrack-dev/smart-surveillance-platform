package com.coltrack.recordingservice.service;

import com.coltrack.recordingservice.client.CameraClient;
import com.coltrack.recordingservice.dto.CameraDto;
import com.coltrack.recordingservice.model.RecordingSession;
import com.coltrack.recordingservice.model.RecordingStatus;
import com.coltrack.recordingservice.worker.RecordingListener;
import com.coltrack.recordingservice.worker.RecordingWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages recording lifecycle.
 * <p>
 * Responsible for:
 * - starting recording;
 * - stopping recording;
 * - tracking active recordings;
 * - receiving worker lifecycle events.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordingManager implements RecordingListener {

    private final RecordingStorageService storageService;
    private final CameraClient cameraClient;
    /**
     * Active recording sessions.
     * <p>
     * Key - camera id.
     * Value - recording session.
     */
    private final Map<UUID, RecordingSession> sessions =
            new ConcurrentHashMap<>();


    /**
     * Starts recording for camera.
     * <p>
     * Called when StreamStartedEvent received.
     */
    public RecordingSession start(UUID cameraId) {

        RecordingSession existing = sessions.get(cameraId);

        if (existing != null) {
            if (existing.getStatus() == RecordingStatus.RECORDING ||
                    existing.getStatus() == RecordingStatus.STARTING) {
                log.info(
                        "Recording already running camera={}",
                        cameraId
                );
                return existing;
            }
            log.warn(
                    "Removing stale recording session camera={}",
                    cameraId
            );
            sessions.remove(cameraId);
        }

        CameraDto camera =
                cameraClient.findById(cameraId);

        log.info("start recording for {}", camera.rtspUrl());

        RecordingSession session =
                RecordingSession.builder()
                        .id(UUID.randomUUID())
                        .cameraId(cameraId)
                        .rtspUrl(camera.rtspUrl())
                        .status(RecordingStatus.STARTING)
                        .build();
        sessions.put(
                cameraId,
                session
        );

        log.info(
                "Creating recording session camera={} id={}",
                cameraId,
                session.getId()
        );

        Thread.startVirtualThread(
                () -> {
                    try {
                        log.info(
                                "Starting RecordingWorker camera={}",
                                cameraId
                        );
                        RecordingWorker worker =
                                new RecordingWorker(
                                        session,
                                        storageService,
                                        camera.rtspUrl(),
                                        this
                                );
                        worker.run();
                    } catch (Exception e) {
                        log.error(
                                "Recording worker failed camera={}",
                                cameraId,
                                e
                        );
                        session.setStatus(
                                RecordingStatus.FAILED
                        );
                    }
                }
        );
        log.info(
                "Recording worker started camera={}",
                cameraId
        );
        return session;
    }

    /**
     * Stops recording.
     * <p>
     * Called when StreamStoppedEvent received.
     */
    public void stop(UUID cameraId) {

        RecordingSession session = sessions.get(cameraId);

        if (session == null) {
            log.warn(
                    "Recording session not found camera={}",
                    cameraId
            );
            return;
        }

        log.info(
                "Requesting recording stop camera={}",
                cameraId
        );

        session.setStopRequested(true);

        session.setStatus(
                RecordingStatus.STOPPING
        );

        /*
         * Do not kill FFmpeg here.
         *
         * RecordingWorker owns the process lifecycle.
         * It will send SIGTERM and wait until FFmpeg
         * writes the container trailer.
         */
    }

    @Override
    public void started(RecordingSession session) {

        log.info("Recording started camera={}", session.getCameraId());

        session.setStatus(RecordingStatus.RECORDING);
    }

    @Override
    public void stopped(RecordingSession session) {

        log.info("Recording stopped camera={}", session.getCameraId());

        session.setStatus(
                RecordingStatus.STOPPED
        );
    }

    @Override
    public void failed(RecordingSession session) {

        log.error("Recording failed camera={} error={}", session.getCameraId(), session.getLastError());

        session.setStatus(
                RecordingStatus.FAILED
        );
    }

    /**
     * Returns recording session.
     */
    public RecordingSession find(UUID cameraId) {

        return sessions.get(cameraId);
    }

    /**
     * Returns all active recordings.
     */
    public Collection<RecordingSession> findAll() {
        return sessions.values();
    }

    /**
     * Removes recording session.
     */
    public void remove(UUID cameraId) {

        log.info("Removing recording session camera={}", cameraId);

        sessions.remove(cameraId);
    }
}
