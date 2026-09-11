package com.coltrack.analyticsservice.client;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class CameraConnectionClient {
    private final RestClient cameraRestClient;

    public CameraConnection connection(UUID cameraId) {
        CameraConnection connection = cameraRestClient.get()
                .uri("/internal/cameras/{id}/connection", cameraId)
                .retrieve()
                .body(CameraConnection.class);
        if (connection == null || connection.rtspUrl() == null
                || connection.rtspUrl().isBlank()) {
            throw new IllegalStateException("Camera has no resolved RTSP URL: " + cameraId);
        }
        return connection;
    }

    public record CameraConnection(UUID id, String rtspUrl, String videoProcessingMode) {
    }
}
