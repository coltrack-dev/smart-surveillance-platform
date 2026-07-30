package com.coltrack.streamservice.client;

import com.coltrack.streamservice.client.dto.CameraDto;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class CameraClient {

    private final RestClient restClient;

    public CameraDto findById(UUID cameraId) {

        return restClient.get()
                .uri("/api/cameras/{id}", cameraId)
                .retrieve()
                .body(CameraDto.class);
    }

    public Collection<CameraDto> findAll() {

        CameraDto[] cameras =
                restClient.get()
                        .uri("/api/cameras")
                        .retrieve()
                        .body(CameraDto[].class);

        return cameras == null
                ? List.of()
                : Arrays.asList(cameras);
    }
}
