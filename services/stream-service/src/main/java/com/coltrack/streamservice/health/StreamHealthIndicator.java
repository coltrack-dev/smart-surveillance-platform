package com.coltrack.streamservice.health;

import com.coltrack.streamservice.model.StreamSession;
import com.coltrack.streamservice.model.StreamStatus;
import com.coltrack.streamservice.service.StreamManager;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;


/**
 * Actuator health indicator for camera streams.
 * <p>
 * Checks:
 * - active streams;
 * - FFmpeg process state;
 * - stream status;
 * - last frame timestamp.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StreamHealthIndicator implements HealthIndicator {

    private static final long MAX_FRAME_DELAY_SECONDS = 10;

    private final StreamManager streamManager;

    @Override
    public Health health() {

        Collection<StreamSession> sessions =
                streamManager.findAll();

        long running =
                sessions.stream()
                        .filter(this::isHealthy)
                        .count();

        long failed =
                sessions.stream()
                        .filter(session ->
                                session.getStatus()
                                        == StreamStatus.ERROR)
                        .count();

        log.debug(
                "Stream health check running={} failed={} total={}",
                running,
                failed,
                sessions.size()
        );

        Health.Builder builder;

        if (failed > 0) {

            builder = Health.down();

        } else {

            builder = Health.up();

        }

        return builder
                .withDetail(
                        "totalStreams",
                        sessions.size()
                )
                .withDetail(
                        "runningStreams",
                        running
                )
                .withDetail(
                        "failedStreams",
                        failed
                )
                .build();
    }


    /**
     * Checks if stream is really alive.
     */
    private boolean isHealthy(
            StreamSession session
    ) {

        if (session.getStatus() != StreamStatus.RUNNING) {

            return false;
        }

        Process process = session.getFfmpegProcess();

        if (process == null || !process.isAlive()) {

            return false;
        }

        if (session.getLastFrameTime() == null) {

            return false;
        }

        long delay =
                Duration.between(
                                session.getLastFrameTime(),
                                Instant.now()
                        )
                        .getSeconds();

        return delay < MAX_FRAME_DELAY_SECONDS;
    }
}
