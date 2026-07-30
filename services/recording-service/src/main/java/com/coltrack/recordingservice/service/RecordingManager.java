package com.coltrack.recordingservice.service;

import com.coltrack.recordingservice.model.RecordingSession;
import com.coltrack.recordingservice.model.RecordingStatus;
import com.coltrack.recordingservice.worker.RecordingWorker;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Manages recording lifecycle.
 *
 * Responsible for:
 * - starting recording;
 * - stopping recording;
 * - tracking active recordings.
 */
@Slf4j
@Service
public class RecordingManager {


    /**
     * Active recording sessions.
     *
     * Key - camera id.
     * Value - recording session.
     */
    private final Map<UUID, RecordingSession> sessions =
            new ConcurrentHashMap<>();


    /**
     * Starts recording for camera.
     *
     * Called when StreamStartedEvent received.
     */
    public RecordingSession start(
            UUID cameraId
    ) {

        RecordingSession existing =
                sessions.get(cameraId);

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

        RecordingSession session =
                RecordingSession.builder()
                        .id(UUID.randomUUID())
                        .cameraId(cameraId)
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

                        log.info("Starting RecordingWorker camera={}", cameraId);

                        RecordingWorker worker =
                                new RecordingWorker(
                                        session
                                );

                        worker.run();
                    }
                    catch (Exception e) {

                        log.error("Recording worker failed camera={}", cameraId, e);

                        session.setStatus(
                                RecordingStatus.FAILED
                        );
                    }
                }
        );

        log.info("Recording worker started camera={}", cameraId);

        return session;
    }


    /**
     * Stops recording.
     *
     * Called when StreamStoppedEvent received.
     */
    public void stop(
            UUID cameraId
    ) {

        RecordingSession session =
                sessions.get(cameraId);


        if (session == null) {

            log.warn(
                    "Recording session not found camera={}",
                    cameraId
            );

            return;
        }


        log.info(
                "Stopping recording camera={}",
                cameraId
        );


        session.setStopRequested(true);

        session.setStatus(
                RecordingStatus.STOPPING
        );


        Process process =
                session.getFfmpegProcess();


        if (process != null) {

            log.info(
                    "Destroying ffmpeg recording process camera={}",
                    cameraId
            );


            process.destroyForcibly();
        }
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
