package com.coltrack.recordingservice.worker;

import com.coltrack.recordingservice.model.RecordingEntity;
import com.coltrack.recordingservice.model.RecordingSession;
import com.coltrack.recordingservice.model.RecordingStatus;
import com.coltrack.recordingservice.service.*;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.coltrack.recordingservice.kafka.RecordingEventPublisher;

@Slf4j
public class RecordingWorker implements Runnable {

    private static final int FIRST_SEGMENT_TIMEOUT_SECONDS = 30;
    private static final int OUTPUT_STALE_TIMEOUT_SECONDS = 20;
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final int MAX_RECONNECT_DELAY_SECONDS = 30;

    private final RecordingSession session;
    private final RecordingStorageService storageService;
    private final String rtspUrl;
    private final RecordingListener listener;
    private final RecordingMetadataService recordingMetadataService;
    private final FfprobeService ffprobeService;
    private final RecordingStatisticsService recordingStatisticsService;
    private final S3StorageService s3StorageService;

    private final RecordingEventPublisher recordingEventPublisher;
    private final RecordingEntity metadata;
    private final Path directory;

    private final Deque<String> errorLines = new ArrayDeque<>(30);

    public RecordingWorker(
            RecordingSession session,
            RecordingStorageService storageService,
            RecordingMetadataService recordingMetadataService,
            FfprobeService ffprobeService,
            RecordingStatisticsService recordingStatisticsService,
            S3StorageService s3StorageService,
            RecordingEventPublisher recordingEventPublisher,
            String rtspUrl,
            RecordingEntity metadata,
            Path directory,
            RecordingListener listener
    ) {

        this.session = session;
        this.storageService = storageService;
        this.recordingMetadataService = recordingMetadataService;
        this.ffprobeService = ffprobeService;
        this.recordingStatisticsService = recordingStatisticsService;
        this.s3StorageService = s3StorageService;
        this.recordingEventPublisher = recordingEventPublisher;
        this.rtspUrl = rtspUrl;
        this.metadata = metadata;
        this.directory = directory;
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
        int reconnectAttempt = 0;
        boolean recordingConfirmed = false;

        try {

            storageService.cleanupDirectory(directory);

            while (!session.isStopRequested()) {
                Path outputPattern = directory.resolve(
                        String.format("recording-%03d-%%03d.mkv", reconnectAttempt)
                );

                try {
                    process = startFfmpeg(outputPattern);
                    waitForFirstSegment(process, reconnectAttempt);

                    if (!recordingConfirmed) {
                        recordingConfirmed = true;
                        session.setStatus(RecordingStatus.RECORDING);
                        recordingMetadataService.markRecording(metadata);
                        listener.started(session);
                    }

                    waitUntilStopped(process, reconnectAttempt);

                    if (session.isStopRequested()) {
                        finishRecording(process, metadata, outputPattern);
                        return;
                    }

                    session.setLastError(
                            "FFmpeg exited unexpectedly: " + buildLastError()
                    );

                } catch (Exception attemptFailure) {
                    session.setLastError(attemptFailure.getMessage());
                    log.warn(
                            "Recording attempt failed camera={} recordingId={} attempt={}",
                            session.getCameraId(),
                            session.getId(),
                            reconnectAttempt,
                            attemptFailure
                    );
                } finally {
                    cleanup(process);
                    process = null;
                }

                if (session.isStopRequested()) {
                    finishStoppedWithoutLiveProcess(metadata);
                    return;
                }

                reconnectAttempt++;
                if (reconnectAttempt > MAX_RECONNECT_ATTEMPTS) {
                    throw new IOException(
                            "Recording reconnect limit exceeded. Last error: "
                                    + session.getLastError()
                    );
                }

                sleepBeforeReconnect(reconnectAttempt);
            }

            finishStoppedWithoutLiveProcess(metadata);

        } catch (Exception e) {

            handleFailure(metadata, e);

        } finally {

            cleanup(process);
        }
    }

    private void waitUntilStopped(
            Process process,
            int attempt
    )
            throws Exception {

        Instant lastProgress = Instant.now();
        long lastSize = -1;

        while (process.isAlive()) {

            if (session.isStopRequested()) {

                stopProcessGracefully(process);

                break;
            }

            long currentSize = calculateAttemptSize(attempt);
            if (currentSize != lastSize) {
                lastSize = currentSize;
                lastProgress = Instant.now();
                session.setSizeBytes(currentSize);
            } else if (Duration.between(lastProgress, Instant.now()).getSeconds()
                    > OUTPUT_STALE_TIMEOUT_SECONDS) {
                throw new IOException(
                        "Recording output has not changed for more than "
                                + OUTPUT_STALE_TIMEOUT_SECONDS + " seconds"
                );
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
        session.setFilePath(outputPattern.getParent().toString());

        startStdoutReader(process);
        startStderrReader(process);

        return process;
    }

    private void waitForFirstSegment(
            Process process,
            int attempt
    ) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis()
                + FIRST_SEGMENT_TIMEOUT_SECONDS * 1000L;

        while (System.currentTimeMillis() < deadline) {
            if (calculateAttemptSize(attempt) > 0) {
                return;
            }
            if (!process.isAlive()) {
                throw new IOException(
                        "FFmpeg exited before writing the first segment: "
                                + buildLastError()
                );
            }
            Thread.sleep(500);
        }

        throw new IOException(
                "Timed out waiting for first recording segment ("
                        + FIRST_SEGMENT_TIMEOUT_SECONDS + " seconds)"
        );
    }

    private long calculateAttemptSize(int attempt) throws IOException {
        String prefix = String.format("recording-%03d-", attempt);
        try (Stream<Path> files = Files.list(directory)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .filter(path -> path.getFileName().toString().endsWith(".mkv"))
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException exception) {
                            throw new IllegalStateException(exception);
                        }
                    })
                    .sum();
        } catch (IllegalStateException exception) {
            if (exception.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw exception;
        }
    }

    private void sleepBeforeReconnect(int attempt) throws InterruptedException {
        long delaySeconds = Math.min(
                1L << Math.min(attempt - 1, 5),
                MAX_RECONNECT_DELAY_SECONDS
        );
        log.info(
                "Recording reconnect backoff camera={} recordingId={} delaySeconds={}",
                session.getCameraId(),
                session.getId(),
                delaySeconds
        );
        Thread.sleep(delaySeconds * 1000L);
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

        session.setExitCode(process.waitFor());
        completeStoppedRecording(metadata, outputPattern.getParent());
    }

    private void finishStoppedWithoutLiveProcess(
            RecordingEntity metadata
    ) throws Exception {
        completeStoppedRecording(metadata, directory);
    }

    private void completeStoppedRecording(
            RecordingEntity metadata,
            Path recordingDirectory
    ) throws Exception {

        Instant finishedAt =
                Instant.now();

        session.setFinishedAt(
                finishedAt
        );

        session.setDurationSeconds(
                Duration.between(
                        session.getStartedAt(),
                        finishedAt
                ).toSeconds()
        );

        session.setSizeBytes(
                storageService.calculateDirectorySize(
                        recordingDirectory
                )
        );

        session.setSegmentsCount(
                storageService.countSegments(
                        recordingDirectory
                )
        );

        Path firstSegment = storageService.findFirstSegment(recordingDirectory);
        if (firstSegment == null || session.getSizeBytes() == 0) {
            throw new IOException(
                    "Recording stopped without a non-empty media segment"
            );
        }

        ffprobeService.fillMetadata(
                session,
                firstSegment
        );

        session.setStatus(
                RecordingStatus.STOPPED
        );

        /*
         * Persist the parent recording before starting an upload or
         * publishing RECORDING_READY.
         */
        recordingMetadataService.complete(
                metadata,
                session
        );

        listener.stopped(
                session
        );

        if (!metadata.getId().equals(session.getId())) {

            throw new IllegalStateException(
                    "Recording ID mismatch: metadata="
                            + metadata.getId()
                            + ", session="
                            + session.getId()
            );
        }

        Thread.startVirtualThread(() -> {

            try {

                s3StorageService.uploadRecording(session);

                log.info("S3 upload completed recordingId={}, camera={}", session.getId(), session.getCameraId());

                if (session.isUploaded()) {

                    recordingEventPublisher.publishReady(session);
                } else {
                    log.info("RecordingReadyEvent not published because " + "S3 upload is disabled recordingId={}", session.getId());
                }

            } catch (Exception exception) {

                log.error(
                        "S3 upload failed recordingId={}, camera={}",
                        session.getId(),
                        session.getCameraId(),
                        exception
                );
            }
        });
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


                "-rw_timeout",
                "15000000",


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
