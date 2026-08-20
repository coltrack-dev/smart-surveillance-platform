package com.coltrack.recordingservice.service;

import com.coltrack.recordingservice.client.CameraClient;
import com.coltrack.recordingservice.dto.CameraDto;
import com.coltrack.recordingservice.model.RecordingSession;
import com.coltrack.recordingservice.model.RecordingEntity;
import com.coltrack.recordingservice.model.RecordingStatus;
import com.coltrack.recordingservice.worker.RecordingListener;
import com.coltrack.recordingservice.worker.RecordingWorker;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

import com.coltrack.recordingservice.kafka.RecordingEventPublisher;

import java.time.Instant;
import java.nio.file.Path;
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
    private final RecordingEventPublisher recordingEventPublisher;

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


    private void startWorker(
            RecordingSession session,
            RecordingEntity metadata,
            Path directory
    ) {

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
                                        recordingEventPublisher,
                                        session.getRtspUrl(),
                                        metadata,
                                        directory,
                                        this
                                );

                        worker.run();
                    } catch (Exception e) {

                        log.error("Recording worker failed camera={}", session.getCameraId(), e);

                        session.setStatus(
                                RecordingStatus.FAILED
                        );
                        session.setLastError(e.getMessage());
                        recordingMetadataService.failed(metadata, e.getMessage());
                        failed(session);
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
    public RecordingSession start(
            UUID cameraId,
            UUID recordingId,
            Instant eventTime
    ) {

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

                    /*
                     * StreamStartedEvent.eventId is used as recordingId. A
                     * redelivered Kafka event therefore addresses the same DB
                     * row instead of creating a duplicate recording.
                     */
                    if (recordingMetadataService.find(recordingId).isPresent()) {
                        log.info(
                                "Recording event already persisted recordingId={} camera={}",
                                recordingId,
                                cameraId
                        );
                        return null;
                    }

                    CameraDto camera =
                            cameraClient.findById(cameraId);

                    Instant startedAt = eventTime != null
                            ? eventTime
                            : Instant.now();

                    Path directory = storageService.createRecordingDirectory(
                            cameraId,
                            recordingId
                    );

                    RecordingSession newSession =
                            RecordingSession.builder()
                                    .id(recordingId)
                                    .cameraId(cameraId)
                                    .rtspUrl(camera.rtspUrl())
                                    .status(RecordingStatus.STARTING)
                                    .startedAt(startedAt)
                                    .filePath(directory.toString())
                                    .build();

                    log.info("Creating recording session camera={} id={}", cameraId, newSession.getId());


                    /*
                     * Persist synchronously before returning to the Kafka
                     * listener. If this transaction fails, the listener throws
                     * and Kafka can redeliver the StreamStartedEvent.
                     */
                    RecordingEntity metadata = recordingMetadataService.create(
                            recordingId,
                            cameraId,
                            directory.toString(),
                            startedAt
                    );

                    startWorker(newSession, metadata, directory);


                    return newSession;
                });


        return session;
    }

    /**
     * Reconciles rows left in an active state after an unclean service stop.
     * They cannot be silently reported as live because their FFmpeg process no
     * longer exists. A subsequent StreamStartedEvent creates a new session.
     */
    @Scheduled(
            initialDelayString = "${recording.recovery.initial-delay:30s}",
            fixedDelayString = "${recording.recovery.scan-delay:1h}"
    )
    public void reconcileInterruptedRecordings() {
        recordingMetadataService.findIncomplete().forEach(recording -> {
            RecordingSession activeSession = sessions.get(recording.getCameraId());
            if (activeSession != null
                    && recording.getId().equals(activeSession.getId())) {
                return;
            }
            log.warn(
                    "Marking interrupted recording as failed recordingId={} camera={}",
                    recording.getId(),
                    recording.getCameraId()
            );
            recordingMetadataService.failed(
                    recording,
                    "Recording service restarted before recording completed"
            );
        });
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
