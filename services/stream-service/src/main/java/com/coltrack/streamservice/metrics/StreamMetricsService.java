package com.coltrack.streamservice.metrics;

import com.coltrack.streamservice.model.StreamSession;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

@Slf4j
@Service
public class StreamMetricsService {

    private final MeterRegistry registry;

    /**
     * Live collection of active stream sessions.
     * <p>
     * StreamManager provides ConcurrentHashMap.values(),
     * therefore changes in sessions are visible automatically.
     */
    private Collection<StreamSession> sessions = List.of();


    public StreamMetricsService(
            MeterRegistry registry
    ) {
        this.registry = registry;
    }


    /**
     * Configure stream session source.
     * <p>
     * Called by StreamManager after initialization.
     */
    public void setSessions(
            Collection<StreamSession> sessions
    ) {

        this.sessions = sessions;

        log.info(
                "Stream metrics session source configured"
        );
    }


    /**
     * Register global stream metrics.
     */
    @PostConstruct
    public void registerMetrics() {

        Gauge.builder(
                        "stream_active_count",
                        this,
                        service -> service.sessions
                                .stream()
                                .filter(StreamSession::isRunning)
                                .count()
                )
                .description(
                        "Number of active running streams"
                )
                .register(registry);


        log.info(
                "Stream metrics registered"
        );
    }


    /**
     * Register metrics for specific camera stream.
     * <p>
     * Metrics:
     * - last frame delay;
     * - reconnect counter;
     * - stream status.
     */
    public void registerSessionMetrics(
            StreamSession session
    ) {

        String cameraId =
                session.getCameraId()
                        .toString();


        Gauge.builder(
                        "stream_last_frame_age_seconds",
                        session,
                        s -> {

                            if (s.getLastFrameTime() == null) {

                                return -1;
                            }

                            return Duration.between(
                                            s.getLastFrameTime(),
                                            Instant.now()
                                    )
                                    .toSeconds();
                        }
                )
                .tag(
                        "camera",
                        cameraId
                )
                .description(
                        "Seconds since last received frame"
                )
                .register(registry);


        Gauge.builder(
                        "stream_reconnect_count",
                        session,
                        StreamSession::getReconnectCount
                )
                .tag(
                        "camera",
                        cameraId
                )
                .description(
                        "Number of stream reconnect attempts"
                )
                .register(registry);


        Gauge.builder(
                        "stream_status",
                        session,
                        s -> {

                            if (s.getStatus() == null) {

                                return -1;
                            }

                            return switch (session.getSafeStatus()) {

                                case RUNNING ->
                                        1;

                                case STARTING ->
                                        2;

                                case RECONNECTING ->
                                        3;

                                case STOPPING ->
                                        4;

                                case STOPPED ->
                                        0;

                                case ERROR ->
                                        -1;
                            };
                        }
                )
                .tag(
                        "camera",
                        cameraId
                )
                .description(
                        "Stream state: -1 unknown, 0 stopped, 1 running, 2 reconnecting, 3 error, 4 starting"
                )
                .register(registry);

        log.info(
                "Registered stream metrics camera={}",
                cameraId
        );
    }
}