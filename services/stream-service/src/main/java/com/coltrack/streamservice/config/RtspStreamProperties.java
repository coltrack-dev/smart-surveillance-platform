package com.coltrack.streamservice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "stream.rtsp")
public class RtspStreamProperties {
    private Duration probeTimeout = Duration.ofSeconds(10);
    private Duration playlistTimeout = Duration.ofSeconds(30);
    private Duration stalePlaylistTimeout = Duration.ofSeconds(15);
    private Duration reconnectMaxDelay = Duration.ofSeconds(30);
}
