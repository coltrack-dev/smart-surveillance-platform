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
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class StreamMetricsService {


    private final MeterRegistry registry;


    /**
     * Active stream sessions.
     *
     * StreamManager provides live ConcurrentHashMap values.
     */
    private Collection<StreamSession> sessions =
            Collections.emptyList();


    /**
     * Registered camera metrics.
     */
    private final Set<String> registeredCameras =
            ConcurrentHashMap.newKeySet();


    public StreamMetricsService(
            MeterRegistry registry
    ) {
        this.registry = registry;
    }


    /**
     * Configure stream session source.
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
     * Global stream metrics.
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
                "Global stream metrics registered"
        );
    }


    /**
     * Register metrics for camera stream.
     */
    public void registerSessionMetrics(
            StreamSession session
    ) {


        String cameraId =
                session.getCameraId()
                        .toString();


        if (!registeredCameras.add(cameraId)) {

            log.debug(
                    "Metrics already registered camera={}",
                    cameraId
            );

            return;
        }


        /*
         * Static camera information.
         *
         * Example:
         *
         * stream_info{
         *   camera="f9bb..."
         * } 1
         */
        Gauge.builder(
                        "stream_info",
                        session,
                        s -> 1
                )
                .tag(
                        "camera",
                        cameraId
                )
                .description(
                        "Camera stream information"
                )
                .register(registry);



        /*
         * Seconds since last frame.
         *
         * Example:
         *
         * stream_last_frame_age_seconds{
         *   camera="f9bb..."
         * } 0.8
         */
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
                                    .toMillis() / 1000.0;
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



        /*
         * Reconnect attempts counter.
         */
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



        /*
         * Stream lifecycle state.
         *
         * Values:
         *
         * -1 ERROR
         *  0 STOPPED
         *  1 RUNNING
         *  2 STARTING
         *  3 RECONNECTING
         *  4 STOPPING
         */
        Gauge.builder(
                        "stream_status",
                        session,
                        s -> mapStatus(
                                s.getSafeStatus()
                        )
                )
                .tag(
                        "camera",
                        cameraId
                )
                .description(
                        "Stream lifecycle status"
                )
                .register(registry);



        log.info(
                "Registered stream metrics camera={}",
                cameraId
        );
    }



    private int mapStatus(
            com.coltrack.streamservice.model.StreamStatus status
    ) {


        return switch (status) {

            case ERROR ->
                    -1;

            case STOPPED ->
                    0;

            case RUNNING ->
                    1;

            case STARTING ->
                    2;

            case RECONNECTING ->
                    3;

            case STOPPING ->
                    4;
        };
    }
}
