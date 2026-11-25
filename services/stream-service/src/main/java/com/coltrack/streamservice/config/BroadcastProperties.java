package com.coltrack.streamservice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

import java.time.Duration;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "broadcast")
public class BroadcastProperties {
    private String managementToken;
    private String securityMode = "legacy";
    private String playbackSecret;
    private Duration playbackTtl = Duration.ofMinutes(10);
    private Duration viewerSessionTtl = Duration.ofSeconds(90);
    private String playerBaseUrl;
    private String mediaReadUsername;
    private String mediaReadPassword;
    private String mediaHlsBaseUrl;
    private int rateLimitPerMinute = 300;

    @PostConstruct
    void validate() {
        if (!"oidc".equalsIgnoreCase(securityMode)
                && (managementToken == null || managementToken.length() < 32)) {
            throw new IllegalStateException("BROADCAST_MANAGEMENT_TOKEN must be at least 32 characters");
        }
        if (playbackSecret == null || playbackSecret.length() < 32) {
            throw new IllegalStateException("BROADCAST_PLAYBACK_SECRET must be at least 32 characters");
        }
        if (playbackTtl == null || playbackTtl.isNegative() || playbackTtl.isZero()
                || playbackTtl.compareTo(Duration.ofMinutes(30)) > 0) {
            throw new IllegalStateException("BROADCAST_PLAYBACK_TTL must be between 1 second and 30 minutes");
        }
        if (viewerSessionTtl == null || viewerSessionTtl.compareTo(Duration.ofSeconds(30)) < 0
                || viewerSessionTtl.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalStateException("BROADCAST_VIEWER_SESSION_TTL must be between 30 seconds and 10 minutes");
        }
        if (rateLimitPerMinute < 1) {
            throw new IllegalStateException("BROADCAST_RATE_LIMIT_PER_MINUTE must be positive");
        }
    }
}
