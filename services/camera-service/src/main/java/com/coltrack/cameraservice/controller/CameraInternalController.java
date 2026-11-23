package com.coltrack.cameraservice.controller;

import com.coltrack.cameraservice.dto.CameraConnectionResponse;
import com.coltrack.cameraservice.dto.CameraResourceResponse;
import com.coltrack.cameraservice.dto.CameraOwnershipResponse;
import com.coltrack.cameraservice.entity.CameraEntity;
import com.coltrack.cameraservice.service.CameraService;
import com.coltrack.cameraservice.repository.CameraDeletionTombstoneRepository;
import com.coltrack.identity.security.TenantWorkloadJwt;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/internal/cameras")
@RequiredArgsConstructor
public class CameraInternalController {
    private final CameraService cameraService;
    private final CameraDeletionTombstoneRepository tombstones;

    @GetMapping("/{id}/ownership")
    public CameraOwnershipResponse ownership(@PathVariable UUID id, @AuthenticationPrincipal Jwt workload) {
        if (!TenantWorkloadJwt.isOwnershipLookup(workload)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var camera = cameraService.findOptionalById(id);
        if (camera.isPresent()) {
            return new CameraOwnershipResponse(id, camera.get().getTenantId(), "ACTIVE", camera.get().getCreatedAt());
        }
        return tombstones.find(id)
                .map(tombstone -> new CameraOwnershipResponse(id, tombstone.tenantId(), "DELETED", tombstone.deletedAt()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @GetMapping
    public List<CameraResourceResponse> list(@AuthenticationPrincipal Jwt workload) {
        UUID tenantId = tenantId(workload);
        return cameraService.findAll(tenantId, PageRequest.of(0, 1000)).map(CameraResourceResponse::from).getContent();
    }

    @GetMapping("/{id}")
    public CameraResourceResponse find(@PathVariable UUID id, @AuthenticationPrincipal Jwt workload) {
        return CameraResourceResponse.from(cameraService.findById(id, tenantId(workload)));
    }

    @GetMapping("/{id}/connection")
    public CameraConnectionResponse connection(@PathVariable UUID id, @AuthenticationPrincipal Jwt workload) {
        return cameraService.connection(id, tenantId(workload));
    }

    private UUID tenantId(Jwt workload) {
        try {
            return TenantWorkloadJwt.tenantId(workload);
        } catch (RuntimeException invalid) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }
}
