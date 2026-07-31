package com.coltrack.recordingservice.worker;

import com.coltrack.recordingservice.model.RecordingSession;
import com.coltrack.recordingservice.model.RecordingStatus;
import com.coltrack.recordingservice.service.RecordingStorageService;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Worker responsible for camera recording.
 *
 * Responsibilities:
 * - create recording process;
 * - start FFmpeg RTSP -> MP4 conversion;
 * - monitor FFmpeg lifecycle;
 * - handle manual stop;
 * - notify listeners about recording state changes.
 */
@Slf4j
public class RecordingWorker implements Runnable {

    private final RecordingSession session;
    private final RecordingStorageService storageService;
    private final RecordingListener listener;

    public RecordingWorker(
            RecordingSession session,
            RecordingStorageService storageService,
            RecordingListener listener
    ) {
        this.session = session;
        this.storageService = storageService;
        this.listener = listener;
    }

    @Override
    public void run() {
        Process process = null;
        try {
            log.info(
                    "Recording worker started camera={}",
                    session.getCameraId()
            );
            Path outputDirectory =
                    storageService.createRecordingDirectory(
                            session.getCameraId()
                    );
            storageService.cleanupDirectory(
                    outputDirectory
            );
            log.info(
                    "Recording directory prepared camera={} path={}",
                    session.getCameraId(),
                    outputDirectory
            );
            process =
                    new ProcessBuilder(
                            buildCommand(outputDirectory)
                    )
                            .redirectErrorStream(true)
                            .start();
            session.setFfmpegProcess(process);
            log.info(
                    "FFmpeg recording started camera={} pid={}",
                    session.getCameraId(),
                    process.pid()
            );
            session.setStatus(
                    RecordingStatus.RECORDING
            );
            session.setStartedAt(
                    Instant.now()
            );
            listener.started(session);
            monitorProcess(process);
            int exitCode =
                    process.waitFor();
            if (session.isStopRequested()) {
                log.info(
                        "Recording stopped manually camera={} exitCode={}",
                        session.getCameraId(),
                        exitCode
                );
                session.setStatus(
                        RecordingStatus.STOPPED
                );
                session.setFinishedAt(
                        Instant.now()
                );
                listener.stopped(session);
                return;
            }
            log.warn(
                    "FFmpeg recording finished unexpectedly camera={} exitCode={}",
                    session.getCameraId(),
                    exitCode
            );
            session.setLastError(
                    "FFmpeg exited with code " + exitCode
            );
            session.setStatus(
                    RecordingStatus.FAILED
            );
            listener.failed(session);
        }
        catch (Exception e) {
            if (session.isStopRequested()) {
                log.info(
                        "Recording shutdown requested camera={}",
                        session.getCameraId()
                );
            }
            else {
                session.setStatus(
                        RecordingStatus.FAILED
                );
                session.setLastError(
                        e.getMessage()
                );
                listener.failed(session);
                log.error(
                        "Recording failed camera={}",
                        session.getCameraId(),
                        e
                );
            }
        }
        finally {
            if (process != null && process.isAlive()) {
                log.info(
                        "Destroying FFmpeg recording process camera={}",
                        session.getCameraId()
                );
                process.destroyForcibly();
            }
            session.setFfmpegProcess(null);
            log.info(
                    "Recording worker finished camera={}",
                    session.getCameraId()
            );
        }
    }
    /**
     * Monitors FFmpeg process until stop request or process exit.
     */
    private void monitorProcess(
            Process process
    ) throws InterruptedException {
        while (process.isAlive()) {
            if (session.isStopRequested()) {
                log.info(
                        "Manual stop detected camera={}",
                        session.getCameraId()
                );
                process.destroyForcibly();
                return;
            }
            Thread.sleep(1000);
        }
    }
    /**
     * FFmpeg command:
     *
     * RTSP camera
     *      |
     *      v
     * FFmpeg
     *      |
     *      v
     * MP4 recording segments
     */
    private List<String> buildCommand(
            Path outputDirectory
    ) {
        return List.of(
                "ffmpeg",
                "-rtsp_transport",
                "tcp",
                "-i",
                session.getRtspUrl(),
                "-c",
                "copy",
                "-an",
                "-f",
                "segment",
                "-segment_time",
                "300",
                "-reset_timestamps",
                "1",
                "-strftime",
                "1",
                outputDirectory
                        .resolve("%Y-%m-%d_%H-%M-%S.mp4")
                        .toString()
        );
    }
}
