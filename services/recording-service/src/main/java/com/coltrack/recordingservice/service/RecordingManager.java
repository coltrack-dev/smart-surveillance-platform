package com.coltrack.recordingservice.service;

import com.coltrack.recordingservice.client.CameraClient;
import com.coltrack.recordingservice.dto.CameraDto;
import com.coltrack.recordingservice.model.RecordingSession;
import com.coltrack.recordingservice.model.RecordingStatus;
import com.coltrack.recordingservice.worker.RecordingListener;
import com.coltrack.recordingservice.worker.RecordingWorker;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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
    private final RecordingMetadataService recordingMetadataService;
    private final CameraClient cameraClient;
    private final RecordingStatisticsService recordingStatisticsService;
    private final FfprobeService ffprobeService;
    private final S3StorageService s3StorageService;

    /**
     * Active recording sessions.
     * <p>
     * Key - camera id.
     * Value - recording session.
     */
    private final Map<UUID, RecordingSession> sessions = new ConcurrentHashMap<>();


    @PreDestroy
    public void shutdown() {

        log.info("Stopping all recordings...");

        for (RecordingSession session : sessions.values()) {

            session.setStopRequested(true);

            Process process = session.getFfmpegProcess();

            if (process == null || !process.isAlive()) {
                continue;
            }

            log.info("Stopping ffmpeg camera={}", session.getCameraId());

            process.destroy();

            try {
                if (!process.waitFor(30, TimeUnit.SECONDS)) {

                    log.warn("Force killing ffmpeg camera={}", session.getCameraId());
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                }

            } catch (InterruptedException e) {

                Thread.currentThread().interrupt();

                process.destroyForcibly();
            }
        }

        log.info("All recording processes stopped");
    }


    private void startWorker(RecordingSession session) {

        Thread.startVirtualThread(
                () -> {
                    try {
                        log.info("Starting RecordingWorker camera={}", session.getCameraId());

                        RecordingWorker worker =
                                new RecordingWorker(
                                        session,
                                        storageService,
                                        recordingMetadataService,
                                        ffprobeService,
                                        recordingStatisticsService,
                                        s3StorageService,
                                        session.getRtspUrl(),
                                        this
                                );

                        worker.run();
                    } catch (Exception e) {

                        log.error("Recording worker failed camera={}", session.getCameraId(), e);

                        session.setStatus(
                                RecordingStatus.FAILED
                        );
                    }
                }
        );

        log.info("Recording worker started camera={}", session.getCameraId());
    }

    /**
     * Starts recording for camera.
     * <p>
     * Called when StreamStartedEvent received.
     */
    public RecordingSession start(UUID cameraId, Instant eventTime) {

        RecordingSession session =
                sessions.compute(cameraId, (id, existing) -> {

                    if (existing != null) {

                        /*
                         * Ignore duplicate start events.
                         */
                        if (existing.getStatus() == RecordingStatus.RECORDING ||
                                existing.getStatus() == RecordingStatus.STARTING) {

                            log.info("Recording already running camera={}", cameraId);

                            return existing;
                        }

                        /*
                         * Ignore old Kafka events.
                         */
                        if (existing.getStartedAt() != null &&
                                eventTime != null &&
                                existing.getStartedAt().isAfter(eventTime)) {

                            log.warn("Ignoring old start event camera={} eventTime={} current={}", cameraId, eventTime, existing.getStartedAt());

                            return existing;
                        }

                        log.warn(
                                "Removing stale recording session camera={}",
                                cameraId
                        );
                    }

                    CameraDto camera =
                            cameraClient.findById(cameraId);

                    RecordingSession newSession =
                            RecordingSession.builder()
                                    .id(UUID.randomUUID())
                                    .cameraId(cameraId)
                                    .rtspUrl(camera.rtspUrl())
                                    .status(RecordingStatus.STARTING)
                                    .startedAt(
                                            eventTime != null
                                                    ? eventTime
                                                    : Instant.now()
                                    )
                                    .build();

                    log.info("Creating recording session camera={} id={}", cameraId, newSession.getId());


                    startWorker(newSession);


                    return newSession;
                });


        return session;
    }

    /**
     * Stops recording.
     * <p>
     * Called when StreamStoppedEvent received.
     */
    public synchronized void stop(UUID cameraId, Instant eventTime) {

        RecordingSession session =
                sessions.get(cameraId);

        if (session == null) {

            log.warn("Recording session not found camera={}", cameraId);
            return;
        }

        if (session.getStartedAt() != null &&
                eventTime.isBefore(session.getStartedAt())) {


            log.warn("Ignoring old stop event camera={} eventTime={} startedAt={}", cameraId, eventTime, session.getStartedAt());

            return;
        }

        log.info("Requesting recording stop camera={}", cameraId);

        session.setStopRequested(true);

        session.setStatus(
                RecordingStatus.STOPPING
        );
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
