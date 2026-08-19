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
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
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

    /** Maximum acceptable age of the playlist while FFmpeg is alive. */
    private static final int STALE_PLAYLIST_SECONDS = 15;

    /** Upper bound for exponential reconnect backoff. */
    private static final int MAX_RECONNECT_DELAY_SECONDS = 30;

    /** Number of recent FFmpeg lines attached to a failure description. */
    private static final int FFMPEG_LOG_LINES = 30;

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

        session.setWorkerRunning(true);

        try {
            Path outputDir =
                    hlsService.createStreamDirectory(
                            session.getCameraId()
                    );

            while (!session.isStopRequested()) {

                Process process = null;

                /*
                 * Keep only a bounded tail of FFmpeg output. It provides the real
                 * cause of exit code 1 without retaining an unlimited log in RAM.
                 */
                Deque<String> ffmpegOutput = new ArrayDeque<>();

                try {

                    /*
                     * A playlist left by an earlier attempt must not make the new
                     * process appear ready before it has produced its own segment.
                     */
                    hlsService.cleanupStreamDirectory(outputDir);

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
                                synchronized (ffmpegOutput) {
                                    if (ffmpegOutput.size() == FFMPEG_LOG_LINES) {
                                        ffmpegOutput.removeFirst();
                                    }
                                    ffmpegOutput.addLast(line);
                                }
                                log.info("[ffmpeg] camera={} {}", session.getCameraId(), line);
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
                    Path playlist = outputDir.resolve("index.m3u8");
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

                        /*
                         * A live process does not guarantee a live stream: FFmpeg
                         * can remain alive after the RTSP source stops delivering
                         * packets. Playlist modification time is used as a simple
                         * output watchdog and reflects actual HLS progress.
                         */
                        Instant playlistModified = Files.getLastModifiedTime(playlist).toInstant();
                        if (Duration.between(playlistModified, Instant.now()).getSeconds()
                                > STALE_PLAYLIST_SECONDS) {
                            throw new IOException("HLS playlist has not changed for more than "
                                    + STALE_PLAYLIST_SECONDS + " seconds");
                        }
                        session.setLastFrameTime(playlistModified);

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

                    session.setLastError(buildFfmpegError(exitCode, ffmpegOutput));

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

                    session.setLastError(buildFailureMessage(e, ffmpegOutput));

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

        } finally {
            session.setWorkerRunning(false);
            session.setFfmpegProcess(null);
            session.setStatus(StreamStatus.STOPPED);
            listener.stopped(session);
            log.info("Worker stopped camera={}", session.getCameraId());
        }
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

            if (isPlaylistReady(playlist)) {

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

            /*
             * Fast retries handle short camera/network interruptions, while
             * exponential backoff avoids a tight restart loop during a long
             * outage. The delay sequence is 1, 2, 4, 8, 16, 30 seconds.
             */
            int exponent = Math.min(Math.max(session.getReconnectCount() - 1, 0), 5);
            long delaySeconds = Math.min(1L << exponent, MAX_RECONNECT_DELAY_SECONDS);
            log.info("Reconnect backoff camera={} delaySeconds={}",
                    session.getCameraId(), delaySeconds);
            Thread.sleep(delaySeconds * 1000L);

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

                "-hide_banner",

                "-loglevel",
                "info",

                "-rtsp_transport",
                "tcp",

                "-rw_timeout",
                "15000000",

                "-i",
                session.getRtspUrl(),

                "-map",
                "0:v:0",

                "-c:v",
                "copy",

                "-an",

                "-f",
                "hls",

                "-hls_time",
                "2",

                "-hls_list_size",
                "6",

                "-start_number",
                "0",

                "-hls_segment_filename",
                outputDir
                        .resolve("segment%05d.ts")
                        .toString(),

                "-hls_flags",
                "delete_segments+append_list+independent_segments+omit_endlist+temp_file",

                outputDir
                        .resolve("index.m3u8")
                        .toString()
        );
    }

    static boolean isPlaylistReady(Path playlist) throws IOException {

        /*
         * File existence alone is insufficient: FFmpeg creates the playlist
         * before the first segment may be completely available to a client.
         */
        if (!Files.isRegularFile(playlist) || Files.size(playlist) == 0) {
            return false;
        }
        List<String> lines = Files.readAllLines(playlist);
        if (lines.stream().noneMatch(line -> line.startsWith("#EXTINF:"))) {
            return false;
        }
        for (String line : lines) {
            String value = line.trim();
            if (!value.isEmpty() && !value.startsWith("#")) {
                Path segment = playlist.getParent().resolve(value).normalize();

                /*
                 * Accept readiness only when a referenced, non-empty segment
                 * exists inside the camera directory. startsWith also rejects
                 * malformed playlist entries that escape through "../".
                 */
                if (segment.startsWith(playlist.getParent())
                        && Files.isRegularFile(segment) && Files.size(segment) > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private String buildFfmpegError(int exitCode, Deque<String> output) {
        String details;
        synchronized (output) {
            details = String.join(" | ", output);
        }
        return "FFmpeg exited with code " + exitCode
                + (details.isBlank() ? "" : ". Last output: " + details);
    }

    private String buildFailureMessage(Exception failure, Deque<String> output) {
        String details;
        synchronized (output) {
            details = String.join(" | ", output);
        }
        String message = failure.getMessage() == null
                ? failure.getClass().getSimpleName()
                : failure.getMessage();
        return details.isBlank() ? message : message + ". Last FFmpeg output: " + details;
    }
}
