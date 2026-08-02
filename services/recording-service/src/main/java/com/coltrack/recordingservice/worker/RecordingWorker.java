package com.coltrack.recordingservice.worker;

import com.coltrack.recordingservice.model.RecordingEntity;
import com.coltrack.recordingservice.model.RecordingSession;
import com.coltrack.recordingservice.model.RecordingStatus;
import com.coltrack.recordingservice.service.FfprobeService;
import com.coltrack.recordingservice.service.RecordingMetadataService;
import com.coltrack.recordingservice.service.RecordingStatisticsService;
import com.coltrack.recordingservice.service.RecordingStorageService;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
public class RecordingWorker implements Runnable {

    private final RecordingSession session;
    private final RecordingStorageService storageService;
    private final String rtspUrl;
    private final RecordingListener listener;
    private final RecordingMetadataService recordingMetadataService;
    private final FfprobeService ffprobeService;
    private final RecordingStatisticsService recordingStatisticsService;

    private final Deque<String> errorLines = new ArrayDeque<>(30);

    public RecordingWorker(
            RecordingSession session,
            RecordingStorageService storageService,
            RecordingMetadataService recordingMetadataService,
            FfprobeService ffprobeService,
            RecordingStatisticsService recordingStatisticsService,
            String rtspUrl,
            RecordingListener listener
    ) {
        this.session = session;
        this.storageService = storageService;
        this.recordingMetadataService = recordingMetadataService;
        this.ffprobeService = ffprobeService;
        this.recordingStatisticsService = recordingStatisticsService;
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

        try {

            Path directory = prepareDirectory();

            Path outputPattern = directory.resolve("recording-%03d.mkv");

            process = startFfmpeg(outputPattern);

            metadata = createMetadata(directory);

            waitUntilStopped(process);

            finishRecording(process, metadata, outputPattern);

        } catch (Exception e) {

            handleFailure(metadata, e);

        } finally {

            cleanup(process);
        }
    }

    private Path prepareDirectory() throws IOException {

        Path directory =
                storageService.createRecordingDirectory(
                        session.getCameraId()
                );

        storageService.cleanupDirectory(directory);

        return directory;
    }

    private RecordingEntity createMetadata(Path directory) {

        return recordingMetadataService.create(
                session.getCameraId(),
                directory.toString()
        );
    }

    private void waitUntilStopped(Process process)
            throws Exception {

        while (process.isAlive()) {

            if (session.isStopRequested()) {

                stopProcessGracefully(process);

                break;
            }

            Thread.sleep(1000);
        }
    }

    private Process startFfmpeg(Path outputPattern) throws IOException {

        log.info(
                "Starting recording camera={} pattern={}",
                session.getCameraId(),
                outputPattern
        );

        List<String> command = buildCommand(outputPattern);

        log.info(
                "FFmpeg command: {}",
                String.join(" ", command)
        );

        Process process =
                new ProcessBuilder(command)
                        .start();

        session.setFfmpegProcess(process);
        session.setStatus(RecordingStatus.RECORDING);
        session.setStartedAt(Instant.now());
        session.setFilePath(outputPattern.getParent().toString());

        listener.started(session);

        startStdoutReader(process);
        startStderrReader(process);

        return process;
    }

    private void startStdoutReader(Process process) {

        Thread.startVirtualThread(() -> {

            try (BufferedReader reader =
                         new BufferedReader(
                                 new InputStreamReader(
                                         process.getInputStream()
                                 ))) {

                String line;

                while ((line = reader.readLine()) != null) {
                    log.debug("[ffmpeg][out] {}", line);
                }

            } catch (IOException e) {

                log.debug("FFmpeg stdout reader stopped");
            }
        });
    }

    private void startStderrReader(Process process) {

        Thread.startVirtualThread(() -> {

            try (BufferedReader reader =
                         new BufferedReader(
                                 new InputStreamReader(
                                         process.getErrorStream()
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

            } catch (IOException e) {

                log.debug("FFmpeg stderr reader stopped");
            }
        });
    }

    private void stopProcessGracefully(Process process) {

        if (process == null || !process.isAlive()) {
            return;
        }

        log.info(
                "Stopping recording camera={}",
                session.getCameraId()
        );

        process.destroy();

        try {

            if (!process.waitFor(30, TimeUnit.SECONDS)) {

                log.warn(
                        "FFmpeg graceful stop timeout, forcing kill camera={}",
                        session.getCameraId()
                );

                process.destroyForcibly();

                process.waitFor(2, TimeUnit.SECONDS);
            }

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

            process.destroyForcibly();
        }
    }

    private void finishRecording(
            Process process,
            RecordingEntity metadata,
            Path outputPattern
    ) throws Exception {

        int exitCode = process.waitFor();

        session.setFinishedAt(Instant.now());
        session.setExitCode(exitCode);

        session.setDurationSeconds(
                Duration.between(
                        session.getStartedAt(),
                        session.getFinishedAt()
                ).toSeconds()
        );

        session.setSizeBytes(
                storageService.calculateDirectorySize(outputPattern.getParent())
        );

        session.setSegmentsCount(
                storageService.countSegments(outputPattern.getParent())
        );

        ffprobeService.fillMetadata(
                session,
                storageService.findFirstSegment(outputPattern.getParent())
        );

        if (session.isStopRequested()) {

            session.setStatus(RecordingStatus.STOPPED);

            listener.stopped(session);

            recordingMetadataService.complete(
                    metadata,
                    session
            );

        } else {

            session.setStatus(RecordingStatus.FAILED);

            listener.failed(session);

            recordingMetadataService.failed(
                    metadata,
                    buildLastError()
            );
        }
    }

    private String buildLastError() {

        synchronized (errorLines) {

            if (errorLines.isEmpty()) {
                return "FFmpeg exited without error output";
            }

            return String.join(
                    System.lineSeparator(),
                    errorLines
            );
        }
    }

    private void handleFailure(
            RecordingEntity metadata,
            Exception e
    ) {

        session.setStatus(RecordingStatus.FAILED);

        session.setLastError(e.getMessage());

        listener.failed(session);

        if (metadata != null) {

            recordingMetadataService.failed(
                    metadata,
                    e.getMessage()
            );
        }
    }

    private void cleanup(Process process) {

        if (process != null && process.isAlive()) {

            log.warn(
                    "FFmpeg still alive after worker finish, terminating camera={}",
                    session.getCameraId()
            );

            process.destroy();

            try {

                if (!process.waitFor(
                        5,
                        TimeUnit.SECONDS
                )) {

                    log.warn(
                            "Force killing FFmpeg camera={}",
                            session.getCameraId()
                    );

                    process.destroyForcibly();

                    process.waitFor(
                            2,
                            TimeUnit.SECONDS
                    );
                }

            } catch (InterruptedException e) {

                Thread.currentThread().interrupt();

                process.destroyForcibly();
            }
        }

        session.setFfmpegProcess(null);
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
