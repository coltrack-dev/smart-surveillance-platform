package com.coltrack.recordingservice.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.UUID;

@Component
public class StreamClient {

    private final RestClient restClient;

    public StreamClient(
            RestClient.Builder builder,
            @Value("${stream-service.url}") String streamServiceUrl
    ) {
        this.restClient = builder
                .baseUrl(streamServiceUrl)
                .build();
    }

    public void start(UUID cameraId) {
        restClient.post()
                .uri("/api/streams/{cameraId}/start", cameraId)
                .retrieve()
                .toBodilessEntity();
    }
}
