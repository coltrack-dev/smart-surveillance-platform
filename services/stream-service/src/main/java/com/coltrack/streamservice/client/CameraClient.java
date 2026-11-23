package com.coltrack.streamservice.client;

import com.coltrack.streamservice.client.dto.CameraDto;
import com.coltrack.streamservice.client.dto.CameraConnectionDto;
import com.coltrack.streamservice.client.dto.CameraOwnershipDto;
import com.coltrack.streamservice.security.TenantWorkloadTokenService;

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
    private final TenantWorkloadTokenService workloadTokens;

    public CameraDto findById(UUID cameraId) {

        return restClient.get()
                .uri("/api/cameras/{id}", cameraId)
                .retrieve()
                .body(CameraDto.class);
    }

    public CameraDto findById(UUID cameraId, UUID tenantId) {
        return restClient.get().uri("/internal/cameras/{id}", cameraId)
                .headers(headers -> headers.setBearerAuth(workloadTokens.issue(tenantId)))
                .retrieve().body(CameraDto.class);
    }

    public CameraConnectionDto connection(UUID cameraId) {
        return restClient.get()
                .uri("/internal/cameras/{id}/connection", cameraId)
                .retrieve()
                .body(CameraConnectionDto.class);
    }

    public CameraConnectionDto connection(UUID cameraId, UUID tenantId) {
        return restClient.get().uri("/internal/cameras/{id}/connection", cameraId)
                .headers(headers -> headers.setBearerAuth(workloadTokens.issue(tenantId)))
                .retrieve().body(CameraConnectionDto.class);
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

    public Collection<CameraDto> findAll(UUID tenantId) {
        CameraDto[] cameras = restClient.get().uri("/internal/cameras")
                .headers(headers -> headers.setBearerAuth(workloadTokens.issue(tenantId)))
                .retrieve().body(CameraDto[].class);
        return cameras == null ? List.of() : Arrays.asList(cameras);
    }

    /** Tenant ownership is resolved from Camera's persisted row/tombstone, never a Kafka claim. */
    public CameraOwnershipDto ownership(UUID cameraId) {
        return restClient.get().uri("/internal/cameras/{id}/ownership", cameraId)
                .headers(headers -> headers.setBearerAuth(workloadTokens.issueOwnershipLookup()))
                .retrieve().body(CameraOwnershipDto.class);
    }
}
