package com.coltrack.streamservice.worker;

import com.coltrack.streamservice.model.StreamSession;
import com.coltrack.streamservice.model.StreamStatus;
import com.coltrack.streamservice.service.HlsService;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Worker responsible for running FFmpeg process.
 * <p>
 * Responsibilities:
 * - start RTSP -> HLS conversion;
 * - monitor FFmpeg process;
 * - reconnect when FFmpeg exits;
 * - notify StreamListener about stream state changes.
 */
@Slf4j
public class CameraStreamWorker implements Runnable {

    private static final int PLAYLIST_TIMEOUT_SECONDS = 30;
    private static final int RECONNECT_DELAY_SECONDS = 5;

    private final StreamSession session;
    private final HlsService hlsService;
    private final StreamListener listener;

    public CameraStreamWorker(
            StreamSession session,
            HlsService hlsService,
            StreamListener listener
    ) {
        this.session = session;
        this.hlsService = hlsService;
        this.listener = listener;
    }

    @Override
    public void run() {

        Path outputDir =
                hlsService.createStreamDirectory(
                        session.getCameraId()
                );

        hlsService.cleanupStreamDirectory(outputDir);

        while (!session.isStopRequested()) {

            Process process = null;

            try {

                log.info(
                        "Starting FFmpeg camera={}",
                        session.getCameraId()
                );

                process =
                        new ProcessBuilder(
                                buildCommand(outputDir)
                        )
                                .redirectErrorStream(true)
                                .start();

                log.info(
                        "FFmpeg started camera={} pid={}",
                        session.getCameraId(),
                        process.pid()
                );

                /*
                 * Consume FFmpeg output continuously.
                 * Prevents process from blocking because of a full pipe
                 * and provides diagnostics.
                 */
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

                session.setFfmpegProcess(process);
                session.setStatus(StreamStatus.STARTING);

                if (session.getStartedAt() == null) {
                    session.setStartedAt(Instant.now());
                }

                log.info(
                        "Waiting HLS playlist camera={} dir={}",
                        session.getCameraId(),
                        outputDir.toAbsolutePath()
                );

                waitForPlaylist(outputDir, process);

                log.info(
                        "HLS playlist created camera={}",
                        session.getCameraId()
                );

                session.setHlsUrl(
                        hlsService.getStreamUrl(
                                session.getCameraId()
                        )
                );

                session.setStatus(StreamStatus.RUNNING);

                listener.started(session);

                log.info(
                        "Stream started camera={}",
                        session.getCameraId()
                );

                /*
                 * Monitor FFmpeg.
                 */
                while (process.isAlive()) {

                    if (session.isStopRequested()) {

                        log.info(
                                "Manual stop detected camera={}",
                                session.getCameraId()
                        );

                        process.destroy();

                        if (!process.waitFor(
                                5,
                                java.util.concurrent.TimeUnit.SECONDS
                        )) {

                            process.destroyForcibly();
                        }

                        break;
                    }

                    session.setLastFrameTime(
                            Instant.now()
                    );

                    Thread.sleep(1000);
                }

                int exitCode =
                        process.waitFor();

                if (session.isStopRequested()) {

                    log.info(
                            "FFmpeg stopped manually camera={} exitCode={}",
                            session.getCameraId(),
                            exitCode
                    );

                    session.setStatus(StreamStatus.STOPPED);

                    break;
                }

                session.setLastError(
                        "FFmpeg exited with code " + exitCode
                );

                session.setStatus(StreamStatus.ERROR);

                listener.failed(session);

                log.warn(
                        "FFmpeg exited camera={} exitCode={}, reconnecting",
                        session.getCameraId(),
                        exitCode
                );

            } catch (Exception e) {

                log.error("*** Stream failed camera={}", session.getCameraId(), e);

                /*
                 * If playlist was not created,
                 * ensure FFmpeg is not left running.
                 */
                if (process != null && process.isAlive()) {

                    log.warn("Stopping orphan FFmpeg camera={}", session.getCameraId());

                    process.destroyForcibly();

                    try {
                        process.waitFor();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }

                if (session.isStopRequested()) {

                    log.info(
                            "Stream shutdown requested camera={}",
                            session.getCameraId()
                    );

                    break;
                }

                session.setStatus(StreamStatus.ERROR);

                session.setLastError(e.getMessage());

                listener.failed(session);

            } finally {

                if (process != null && process.isAlive()) {

                    log.info(
                            "Destroying FFmpeg process camera={}",
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

            if (!session.isStopRequested()) {

                session.setReconnectCount(
                        session.getReconnectCount() + 1
                );

                session.setStatus(
                        StreamStatus.RECONNECTING
                );

                listener.reconnecting(session);

                sleepBeforeReconnect();
            }
        }

        session.setStatus(StreamStatus.STOPPED);

        listener.stopped(session);

        log.info(
                "Worker stopped camera={}",
                session.getCameraId()
        );
    }

    /**
     * Waits until FFmpeg creates HLS playlist.
     */
    /**
     * Waits until FFmpeg creates the HLS playlist.
     */
    private void waitForPlaylist(
            Path outputDir,
            Process process
    ) throws IOException, InterruptedException {

        Path playlist = outputDir.resolve("index.m3u8");

        log.info(
                "Waiting for HLS playlist camera={} path={}",
                session.getCameraId(),
                playlist.toAbsolutePath()
        );

        long started = System.currentTimeMillis();
        long deadline = started + PLAYLIST_TIMEOUT_SECONDS * 1000L;

        while (System.currentTimeMillis() < deadline) {

            if (Files.exists(playlist)) {

                log.info(
                        "HLS playlist created camera={} after {} ms",
                        session.getCameraId(),
                        System.currentTimeMillis() - started
                );

                return;
            }

            if (!process.isAlive()) {

                throw new IOException(
                        "FFmpeg exited before HLS playlist creation. Exit code="
                                + process.exitValue()
                );
            }

            Thread.sleep(500);
        }

        throw new IOException(
                String.format(
                        "Timed out waiting for HLS playlist (%d s). Path=%s, exists=%s, processAlive=%s",
                        PLAYLIST_TIMEOUT_SECONDS,
                        playlist.toAbsolutePath(),
                        Files.exists(playlist),
                        process.isAlive()
                )
        );
    }

    /**
     * Delay before reconnecting RTSP stream.
     */
    private void sleepBeforeReconnect() {

        try {

            Thread.sleep(
                    RECONNECT_DELAY_SECONDS * 1000L
            );

        } catch (InterruptedException e) {

            Thread.currentThread()
                    .interrupt();

            log.warn(
                    "Reconnect sleep interrupted camera={}",
                    session.getCameraId()
            );
        }
    }

    /**
     * FFmpeg command:
     * <p>
     * RTSP camera
     * |
     * v
     * FFmpeg
     * |
     * v
     * HLS playlist + segments
     */
    private List<String> buildCommand(
            Path outputDir
    ) {

        return List.of(

                "ffmpeg",

                "-rtsp_transport",
                "tcp",

                "-i",
                session.getRtspUrl(),

                "-c:v",
                "copy",

                "-an",

                "-f",
                "hls",

                "-hls_time",
                "2",

                "-hls_list_size",
                "5",

                "-start_number",
                "0",

                "-hls_segment_filename",
                outputDir
                        .resolve("segment%05d.ts")
                        .toString(),

                "-hls_flags",
                "delete_segments+independent_segments+omit_endlist",

                outputDir
                        .resolve("index.m3u8")
                        .toString()
        );
    }
}
