package com.coltrack.recordingservice.worker;

import com.coltrack.recordingservice.model.RecordingEntity;
import com.coltrack.recordingservice.model.RecordingSession;
import com.coltrack.recordingservice.model.RecordingStatus;
import com.coltrack.recordingservice.service.RecordingMetadataService;
import com.coltrack.recordingservice.service.RecordingStorageService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

@Slf4j
public class RecordingWorker implements Runnable {

    private final RecordingSession session;
    private final RecordingStorageService storageService;
    private final String rtspUrl;
    private final RecordingListener listener;
    private final RecordingMetadataService recordingMetadataService;

    public RecordingWorker(
            RecordingSession session,
            RecordingStorageService storageService,
            RecordingMetadataService recordingMetadataService,
            String rtspUrl,
            RecordingListener listener
    ) {
        this.session = session;
        this.storageService = storageService;
        this.recordingMetadataService = recordingMetadataService;
        this.rtspUrl = rtspUrl;
        this.listener = listener;
    }

    private boolean isIgnorableMessage(String line) {

        return line.contains("Non-monotonic DTS")
                || line.contains("co located POCs unavailable")
                || line.contains("mmco: unref short failure");
    }

    @Override
    public void run() {

        Process process = null;

        RecordingEntity metadata = null;

        java.util.Deque<String> errorLines =
                new java.util.ArrayDeque<>(30);

        try {

            Path directory =
                    storageService.createRecordingDirectory(
                            session.getCameraId()
                    );

            storageService.cleanupDirectory(directory);

            /*
             * FFmpeg creates:
             *
             * recording-000.mkv
             * recording-001.mkv
             * recording-002.mkv
             */
            Path outputPattern =
                    directory.resolve(
                            "recording-%03d.mkv"
                    );

            log.info(
                    "Starting recording camera={} pattern={}",
                    session.getCameraId(),
                    outputPattern
            );

            List<String> command =
                    buildCommand(outputPattern);

            log.info(
                    "FFmpeg command: {}",
                    String.join(" ", command)
            );

            /*
             * Start FFmpeg process.
             */
            process =
                    new ProcessBuilder(command)
                            .start();

            session.setFfmpegProcess(process);
            session.setStatus(
                    RecordingStatus.RECORDING
            );
            session.setStartedAt(
                    Instant.now()
            );

            /*
             * Create metadata only after FFmpeg started successfully.
             */
            metadata = recordingMetadataService.create(
                    session.getCameraId(),
                    directory.toString()
            );


            listener.started(session);

            Process finalProcess = process;

            /*
             * Read stdout.
             */
            Thread.startVirtualThread(() -> {

                try (BufferedReader reader =
                             new BufferedReader(
                                     new InputStreamReader(
                                             finalProcess.getInputStream()
                                     ))) {

                    String line;

                    while ((line = reader.readLine()) != null) {
                        log.debug("[ffmpeg][out] {}", line);
                    }

                } catch (Exception ignored) {
                }
            });

            /*
             * Read stderr and remember last messages.
             */
            Thread.startVirtualThread(() -> {

                try (BufferedReader reader =
                             new BufferedReader(
                                     new InputStreamReader(
                                             finalProcess.getErrorStream()
                                     ))) {

                    String line;

                    while ((line = reader.readLine()) != null) {

                        if (isIgnorableMessage(line)) {
                            continue;
                        }

                        log.debug("[ffmpeg][err] {}", line);

                        synchronized (errorLines) {

                            if (errorLines.size() == 30) {
                                errorLines.removeFirst();
                            }

                            errorLines.addLast(line);
                        }
                    }

                } catch (Exception ignored) {
                }
            });

            while (process.isAlive()) {

                if (session.isStopRequested()) {

                    log.info(
                            "Stopping recording camera={}",
                            session.getCameraId()
                    );

                    /*
                     * Graceful stop allows FFmpeg
                     * to write trailer and close file.
                     */
                    process.destroy();

                    boolean exited =
                            process.waitFor(
                                    30,
                                    java.util.concurrent.TimeUnit.SECONDS
                            );

                    if (!exited) {

                        log.warn(
                                "FFmpeg graceful stop timeout, forcing kill camera={}",
                                session.getCameraId()
                        );

                        process.destroyForcibly();
                    }

                    break;
                }

                Thread.sleep(1000);
            }

            int exitCode =
                    process.waitFor();

            session.setFinishedAt(
                    Instant.now()
            );

            if (session.isStopRequested()) {

                session.setStatus(
                        RecordingStatus.STOPPED
                );

                log.info(
                        "Recording stopped camera={} exitCode={}",
                        session.getCameraId(),
                        exitCode
                );

                listener.stopped(session);

                if (metadata != null) {

                    long size = storageService.calculateDirectorySize(
                            outputPattern.getParent()
                    );

                    recordingMetadataService.complete(
                            metadata,
                            size,
                            exitCode
                    );
                }

            } else {

                session.setStatus(
                        RecordingStatus.FAILED
                );

                synchronized (errorLines) {

                    if (!errorLines.isEmpty()) {

                        log.error(
                                "Last FFmpeg messages:\n{}",
                                String.join(
                                        System.lineSeparator(),
                                        errorLines
                                )
                        );
                    }
                }

                log.warn(
                        "Recording failed camera={} exitCode={}",
                        session.getCameraId(),
                        exitCode
                );


                listener.failed(session);

                if (metadata != null) {
                    recordingMetadataService.failed(
                            metadata,
                            ""// todo
                    );
                }
            }

        } catch (Exception e) {

            session.setStatus(
                    RecordingStatus.FAILED
            );


            listener.failed(session);

            log.error(
                    "Recording failed camera={}",
                    session.getCameraId(),
                    e
            );

            if (metadata != null) {
                recordingMetadataService.failed(
                        metadata,
                        e.getMessage()
                );
            }

        } finally {

            if (process != null && process.isAlive()) {

                log.warn(
                        "FFmpeg still alive after worker finish, terminating camera={}",
                        session.getCameraId()
                );

                process.destroy();

                try {

                    if (!process.waitFor(
                            5,
                            java.util.concurrent.TimeUnit.SECONDS
                    )) {

                        process.destroyForcibly();
                    }

                } catch (InterruptedException e) {

                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }

            session.setFfmpegProcess(null);
        }
    }

    private List<String> buildCommand(Path outputPattern) {


        return List.of(

                "ffmpeg",

                "-hide_banner",

                "-loglevel",
                "warning",


                "-rtsp_transport",
                "tcp",


                "-i",
                rtspUrl,


                "-map",
                "0",


                "-c",
                "copy",


                "-f",
                "segment",


                "-segment_time",
                "600",


                "-reset_timestamps",
                "1",


                "-segment_format",
                "matroska",


                "-y",


                outputPattern.toString()
        );
    }
}
