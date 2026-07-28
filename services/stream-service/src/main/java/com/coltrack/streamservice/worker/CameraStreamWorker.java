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


    @Override
    public void run() {
        Process process = null;

        try {
            Path outputDir =
                    hlsService.createStreamDirectory(
                            session.getCameraId()
                    );

            ProcessBuilder builder =
                    new ProcessBuilder(
                            buildCommand(outputDir)
                    );

            builder.redirectErrorStream(true);

            process = builder.start();

            session.setFfmpegProcess(process);
            session.setStatus(StreamStatus.STARTING);
            session.setStartedAt(Instant.now());

            waitForPlaylist(outputDir);

            session.setHlsUrl(
                    hlsService.getStreamUrl(
                            session.getCameraId()
                    )
            );

            session.setStatus(StreamStatus.RUNNING);

            int exitCode = process.waitFor();

            if (exitCode == 0) {
                session.setStatus(StreamStatus.STOPPED);
            } else {
                session.setStatus(StreamStatus.ERROR);
                session.setLastError(
                        "FFmpeg exit code: " + exitCode
                );
            }

        } catch (Exception e) {
            session.setStatus(StreamStatus.ERROR);
            session.setLastError(e.getMessage());

            log.error(
                    "Stream failed camera={}",
                    session.getCameraId(),
                    e
            );
        }
    }

    private void waitForPlaylist(Path outputDir)
            throws InterruptedException {

        Path playlist =
                outputDir.resolve("index.m3u8");

        for (int i = 0; i < 30; i++) {
            if (Files.exists(playlist)) {
                return;
            }

            Thread.sleep(500);
        }

        throw new RuntimeException(
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
