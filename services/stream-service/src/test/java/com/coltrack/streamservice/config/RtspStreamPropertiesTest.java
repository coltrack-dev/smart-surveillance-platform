package com.coltrack.streamservice.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RtspStreamPropertiesTest {

    @Test
    void hasSafeNvrDefaults() {
        RtspStreamProperties properties = new RtspStreamProperties();

        assertThat(properties.getProbeTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.getPlaylistTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.getStalePlaylistTimeout()).isEqualTo(Duration.ofSeconds(15));
        assertThat(properties.getReconnectMaxDelay()).isEqualTo(Duration.ofSeconds(30));
    }
}
