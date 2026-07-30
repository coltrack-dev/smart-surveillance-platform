package com.coltrack.streamservice.worker;

import com.coltrack.streamservice.model.StreamSession;
import com.coltrack.streamservice.model.StreamStatus;
import com.coltrack.streamservice.service.HlsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

@RequiredArgsConstructor
@Slf4j
public class CameraStreamWorker implements Runnable {

    private final StreamSession session;
    private final HlsService hlsService;

    private static final int PLAYLIST_TIMEOUT_SECONDS = 15;
    private static final int RECONNECT_DELAY_SECONDS = 5;

    @Override
    public void run() {

        Path outputDir =
                hlsService.createStreamDirectory(
                        session.getCameraId()
                );

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
                session.setStatus(StreamStatus.STARTING);

                if (session.getStartedAt() == null) {
                    session.setStartedAt(
                            Instant.now()
                    );
                }

                waitForPlaylist(outputDir);

                session.setHlsUrl(
                        hlsService.getStreamUrl(
                                session.getCameraId()
                        )
                );

                session.setStatus(StreamStatus.RUNNING);

                while (process.isAlive()) {

                    session.setLastFrameTime(
                            Instant.now()
                    );

                    Thread.sleep(1000);
                }

                int exitCode =
                        process.waitFor();

                if (session.getStatus() == StreamStatus.STOPPED) {
                    break;
                }

                session.setStatus(StreamStatus.ERROR);
                session.setLastError(
                        "FFmpeg exited: " + exitCode
                );

                log.warn(
                        "FFmpeg stopped. Reconnecting camera={}",
                        session.getCameraId()
                );

            }
            catch (Exception e) {

                if (session.getStatus() == StreamStatus.STOPPED) {
                    break;
                }

                session.setStatus(StreamStatus.ERROR);
                session.setLastError(
                        e.getMessage()
                );

                log.error(
                        "Stream failed camera={}",
                        session.getCameraId(),
                        e
                );
            }
            finally {

                if (process != null) {
                    process.destroyForcibly();
                }

                session.setFfmpegProcess(null);
            }

            session.setReconnectCount(
                    session.getReconnectCount() + 1
            );

            try {

                Thread.sleep(
                        RECONNECT_DELAY_SECONDS * 1000L
                );

            }
            catch (InterruptedException ignored) {

                Thread.currentThread().interrupt();
                break;
            }
        }

        log.info(
                "Worker stopped camera={}",
                session.getCameraId()
        );
    }

    private void waitForPlaylist(
            Path outputDir
    ) throws Exception {

        Path playlist =
                outputDir.resolve("index.m3u8");

        for (int i = 0; i < PLAYLIST_TIMEOUT_SECONDS * 2; i++) {

            if (Files.exists(playlist)) {
                return;
            }

            Thread.sleep(500);
        }

        throw new IOException(
                "HLS playlist was not created"
        );
    }

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
