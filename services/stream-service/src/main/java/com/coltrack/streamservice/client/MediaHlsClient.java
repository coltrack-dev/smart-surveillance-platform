package com.coltrack.streamservice.client;

import com.coltrack.streamservice.config.IngestAgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/**
 * Checks the actual browser-facing HLS output produced by MediaMTX.
 */
@Slf4j
@Component
public class MediaHlsClient {

    private final RestClient restClient;

    public MediaHlsClient(
            RestClient.Builder builder,
            IngestAgentProperties properties
    ) {
        this.restClient = builder
                .baseUrl(properties.getInternalHlsBaseUrl())
                .build();
    }

    /**
     * A master playlist alone is not sufficient: hls.js also needs the media
     * playlist and at least one referenced segment to be available.
     */
    public boolean isReady(UUID cameraId) {
        try {
            String cameraPath = "/" + cameraId + "/";
            String master = getText(cameraPath + "index.m3u8");
            Optional<String> mediaName = safeReference(master, ".m3u8");
            if (mediaName.isEmpty()) {
                return false;
            }

            String media = getText(cameraPath + mediaName.get());
            Optional<String> segmentName = safeReference(media, ".ts", ".m4s");
            if (segmentName.isEmpty()) {
                return false;
            }

            restClient.head()
                    .uri(cameraPath + segmentName.get())
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientException error) {
            log.debug("MediaMTX HLS is not ready camera={}: {}", cameraId, error.getMessage());
            return false;
        }
    }

    private String getText(String path) {
        return restClient.get()
                .uri(path)
                .retrieve()
                .body(String.class);
    }

    /**
     * MediaMTX returns relative file references. Reject absolute URLs and path
     * traversal so playlist contents can never redirect this internal client.
     */
    static Optional<String> safeReference(String playlist, String... suffixes) {
        if (playlist == null) {
            return Optional.empty();
        }
        return Arrays.stream(playlist.split("\\R"))
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .filter(line -> !line.startsWith("/") && !line.contains("://"))
                .filter(line -> !line.contains(".."))
                .filter(line -> Arrays.stream(suffixes).anyMatch(line::endsWith))
                .reduce((first, second) -> second);
    }
}
