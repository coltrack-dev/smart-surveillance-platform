package com.coltrack.streamservice.worker;

import com.coltrack.streamservice.model.StreamSession;
import com.coltrack.streamservice.model.StreamStatus;
import com.coltrack.streamservice.service.HlsService;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Worker responsible for running FFmpeg process.
 *
 * Responsibilities:
 * - start RTSP -> HLS conversion;
 * - monitor FFmpeg process;
 * - reconnect when FFmpeg exits;
 * - notify StreamListener about stream state changes.
 */
@Slf4j
public class CameraStreamWorker implements Runnable {

    private static final int PLAYLIST_TIMEOUT_SECONDS = 15;
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

        // Main reconnect loop.
        // Worker keeps trying until stream is explicitly stopped.
        while (session.getStatus() != StreamStatus.STOPPED) {

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

                session.setFfmpegProcess(process);

                session.setStatus(
                        StreamStatus.STARTING
                );

                if (session.getStartedAt() == null) {

                    session.setStartedAt(
                            Instant.now()
                    );
                }

                // Wait until FFmpeg creates HLS playlist.
                waitForPlaylist(outputDir);

                session.setHlsUrl(
                        hlsService.getStreamUrl(
                                session.getCameraId()
                        )
                );

                session.setStatus(
                        StreamStatus.RUNNING
                );

                // Notify system that stream is available.
                listener.started(session);

                log.info(
                        "Stream started camera={}",
                        session.getCameraId()
                );

                // Monitor FFmpeg process.
                while (process.isAlive()) {

                    session.setLastFrameTime(
                            Instant.now()
                    );

                    Thread.sleep(1000);
                }

                int exitCode =
                        process.waitFor();

                // User manually stopped stream.
                if (session.getStatus() == StreamStatus.STOPPED) {
                    break;
                }

                session.setLastError(
                        "FFmpeg exited with code " + exitCode
                );

                session.setStatus(
                        StreamStatus.ERROR
                );

                listener.failed(session);

                log.warn(
                        "FFmpeg stopped camera={}, reconnecting",
                        session.getCameraId()
                );

            }
            catch (Exception e) {

                // Ignore errors during manual shutdown.
                if (session.getStatus() == StreamStatus.STOPPED) {
                    break;
                }

                session.setStatus(
                        StreamStatus.ERROR
                );

                session.setLastError(
                        e.getMessage()
                );

                listener.failed(session);

                log.error(
                        "Stream failed camera={}",
                        session.getCameraId(),
                        e
                );
            }
            finally {

                // Cleanup FFmpeg process.
                if (process != null) {

                    process.destroyForcibly();

                }

                session.setFfmpegProcess(null);
            }

            // Prepare reconnect attempt.
            if (session.getStatus() != StreamStatus.STOPPED) {

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

        listener.stopped(session);

        log.info(
                "Worker stopped camera={}",
                session.getCameraId()
        );
    }

    /**
     * Waits until FFmpeg creates HLS playlist.
     */
    private void waitForPlaylist(
            Path outputDir
    ) throws Exception {

        Path playlist =
                outputDir.resolve("index.m3u8");

        for (
                int i = 0;
                i < PLAYLIST_TIMEOUT_SECONDS * 2;
                i++
        ) {

            if (Files.exists(playlist)) {
                return;
            }

            Thread.sleep(500);
        }

        throw new IOException(
                "HLS playlist was not created"
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

        }
        catch (InterruptedException e) {

            Thread.currentThread()
                    .interrupt();

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

                "-hls_flags",
                "delete_segments+append_list",

                outputDir
                        .resolve("index.m3u8")
                        .toString()
        );
    }
}
