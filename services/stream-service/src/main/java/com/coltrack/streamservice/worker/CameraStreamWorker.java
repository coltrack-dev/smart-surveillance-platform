package com.coltrack.streamservice.worker;

import com.coltrack.streamservice.model.StreamSession;
import com.coltrack.streamservice.model.StreamStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class CameraStreamWorker implements Runnable {

    private final StreamSession session;

    @Override
    public void run() {

        try {

            Path outputDir =
                    Path.of(
                            "/var/lib/surveillance/streams",
                            session.getCameraId().toString()
                    );

            Files.createDirectories(outputDir);

            ProcessBuilder builder =
                    new ProcessBuilder(buildCommand(outputDir));

            builder.redirectErrorStream(true);

            Process process = builder.start();

            session.setFfmpegProcess(process);
            session.setStatus(StreamStatus.RUNNING);
            session.setStartedAt(Instant.now());

            log.info(
                    "Started stream {}",
                    session.getCameraId()
            );

            int exitCode = process.waitFor();

            session.setStatus(StreamStatus.STOPPED);

            log.warn(
                    "Stream {} stopped. Exit code={}",
                    session.getCameraId(),
                    exitCode
            );

        } catch (Exception e) {

            session.setStatus(StreamStatus.ERROR);
            session.setLastError(e.getMessage());

            log.error(
                    "Camera stream failed {}",
                    session.getCameraId(),
                    e
            );

        }

    }

    private List<String> buildCommand(Path outputDir) {

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

                outputDir.resolve("index.m3u8").toString()

        );

    }

}
