package com.coltrack.recordingservice.client;

import com.coltrack.recordingservice.dto.CameraDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.UUID;


@Component
@RequiredArgsConstructor
public class CameraClient {


    private final RestClient restClient;


    public CameraDto findById(
            UUID cameraId
    ) {


        return restClient.get()
                .uri(
                        "/api/cameras/{id}",
                        cameraId
                )
                .retrieve()
                .body(CameraDto.class);

    }
}
