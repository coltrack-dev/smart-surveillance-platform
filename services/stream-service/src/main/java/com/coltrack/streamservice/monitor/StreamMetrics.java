package com.coltrack.streamservice.monitor;


import io.micrometer.core.instrument.*;

import org.springframework.stereotype.Component;


@Component
public class StreamMetrics {

    private final Gauge activeStreams;

    public StreamMetrics(MeterRegistry registry) {

        activeStreams =
                Gauge.builder(
                                "streams_active",
                                0,
                                value -> value
                        )
                        .register(registry);

    }
}
