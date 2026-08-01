package com.coltrack.recordingservice.worker;

import com.coltrack.recordingservice.model.RecordingSession;
import com.coltrack.recordingservice.model.RecordingStatus;
import com.coltrack.recordingservice.service.RecordingStorageService;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

@Slf4j
public class RecordingWorker implements Runnable {

    private final RecordingSession session;
    private final RecordingStorageService storageService;
    private final String rtspUrl;
    private final RecordingListener listener;

    public RecordingWorker(
            RecordingSession session,
            RecordingStorageService storageService,
            String rtspUrl,
            RecordingListener listener
    ) {
        this.session = session;
        this.storageService = storageService;
        this.rtspUrl = rtspUrl;
        this.listener = listener;
    }

    @Override
    public void run() {

        Process process = null;

        try {

            Path directory =
                    storageService.createRecordingDirectory(
                            session.getCameraId()
                    );

            storageService.cleanupDirectory(directory);

            Path outputFile =
                    directory.resolve(
                            "recording-" +
                                    Instant.now().toEpochMilli() +
                                    ".mkv"
                    );

            log.info(
                    "Starting recording camera={} file={}",
                    session.getCameraId(),
                    outputFile
            );

            List<String> command = buildCommand(outputFile);

            log.info("FFmpeg command: {}", String.join(" ", command));

            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();


            session.setFfmpegProcess(process);
            session.setStatus(RecordingStatus.RECORDING);
            session.setStartedAt(Instant.now());

            listener.started(session);

            Process finalProcess = process;

            Thread.startVirtualThread(() -> {

                try (BufferedReader reader =
                             new BufferedReader(
                                     new InputStreamReader(
                                             finalProcess.getInputStream()
                                     ))) {

                    String line;

                    while ((line = reader.readLine()) != null) {
                        log.debug("[ffmpeg] {}", line);
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
                     * Graceful stop for ffmpeg.
                     * Allows muxer to write trailer/index.
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

            int exitCode = process.waitFor();

            session.setFinishedAt(Instant.now());

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

            } else {

                session.setStatus(
                        RecordingStatus.FAILED
                );

                log.warn(
                        "Recording failed camera={} exitCode={}",
                        session.getCameraId(),
                        exitCode
                );

                listener.failed(session);
            }

        } catch (Exception e) {

            session.setStatus(RecordingStatus.FAILED);

            listener.failed(session);

            log.error(
                    "Recording failed camera={}",
                    session.getCameraId(),
                    e
            );

        }
        finally {

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
                }
                catch (InterruptedException e) {

                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }


            session.setFfmpegProcess(null);
        }
    }

    private List<String> buildCommand(Path outputFile) {
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

                "-c:v",
                "libx264",

                "-preset",
                "veryfast",

                "-pix_fmt",
                "yuv420p",

                "-c:a",
                "aac",

                "-max_muxing_queue_size",
                "1024",

                "-f",
                "matroska",

                "-y",

                outputFile.toString()
        );

    }
}
