package com.coltrack.streamservice.monitor;

import com.coltrack.streamservice.model.StreamSession;
import com.coltrack.streamservice.model.StreamStatus;
import com.coltrack.streamservice.service.StreamManager;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;


@Slf4j
@Component
@RequiredArgsConstructor
public class StreamHealthMonitor {

    private static final long MAX_FRAME_DELAY_SECONDS = 10;

    private final StreamManager streamManager;


    /**
     * Periodically checks active streams.
     *
     * Detects:
     * - FFmpeg process died;
     * - stream stopped producing frames;
     * - stale HLS stream.
     */
    @Scheduled(fixedDelay = 5000)
    public void checkStreams() {

        for (StreamSession session : streamManager.findAll()) {
            check(session);
        }
    }


    private void check(StreamSession session) {

        if (session.getStatus() != StreamStatus.RUNNING) {
            return;
        }

        Process process = session.getFfmpegProcess();

        if (process == null || !process.isAlive()) {

            log.error(
                    "FFmpeg process dead camera={}",
                    session.getCameraId()
            );

            session.setStatus(StreamStatus.ERROR);

            return;
        }

        Instant lastFrame = session.getLastFrameTime();

        if (lastFrame == null) {
            return;
        }

        long delay =
                Duration.between(
                                lastFrame,
                                Instant.now()
                        )
                        .getSeconds();

        if (delay > MAX_FRAME_DELAY_SECONDS) {

            log.warn(
                    "Stream has no frames camera={} delay={}s",
                    session.getCameraId(),
                    delay
            );

            session.setStatus(
                    StreamStatus.ERROR
            );
        }
    }
}
