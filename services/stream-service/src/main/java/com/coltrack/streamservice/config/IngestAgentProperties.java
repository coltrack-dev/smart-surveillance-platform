package com.coltrack.streamservice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "stream.ingest-agent")
public class IngestAgentProperties {

    /**
     * Keeps the existing in-process CameraStreamWorker available as a fallback.
     */
    private boolean enabled;

    private String baseUrl = "http://localhost:8098";

    private String token = "";

    private Duration pollInterval = Duration.ofSeconds(1);

    private String publishRtspBaseUrl = "rtsp://localhost:8554";

    private String internalHlsBaseUrl = "http://localhost:8888";

    private String publicHlsPrefix = "/media-hls";

    private Duration hlsReadyTimeout = Duration.ofSeconds(30);

    private Duration hlsHealthInterval = Duration.ofSeconds(5);

    private int hlsFailureThreshold = 2;

    public String publishUrl(UUID cameraId) {
        return withoutTrailingSlash(publishRtspBaseUrl) + "/" + cameraId;
    }

    public String publicHlsUrl(UUID cameraId) {
        return withoutTrailingSlash(publicHlsPrefix) + "/" + cameraId + "/index.m3u8";
    }

    private String withoutTrailingSlash(String value) {
        return value.endsWith("/")
                ? value.substring(0, value.length() - 1)
                : value;
    }
}
