package com.coltrack.streamservice.controller;

import com.coltrack.identity.security.AuthenticatedPrincipal;
import com.coltrack.identity.security.TenantAuthorization;
import com.coltrack.identity.security.TenantContext;
import com.coltrack.streamservice.client.CameraClient;
import com.coltrack.streamservice.client.dto.CameraDto;
import com.coltrack.streamservice.dto.StreamResponse;
import com.coltrack.streamservice.model.StreamSession;
import com.coltrack.streamservice.service.StreamManager;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.client.HttpClientErrorException;
import java.util.Collection;
import java.util.UUID;

@RestController
@RequestMapping("/api/streams")
@RequiredArgsConstructor
public class StreamController {
    private final StreamManager manager;
    private final CameraClient cameraClient;
    private final TenantAuthorization tenantAuthorization;
    @Value("${app.security.mode:legacy}")
    private String securityMode;

    @PostMapping("/{cameraId}/start")
    public ResponseEntity<StreamResponse> start(@PathVariable UUID cameraId, @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Tenant-ID", required = false) UUID selectedTenant) {
        UUID tenantId = authorize(jwt, selectedTenant, "stream.manage");
        if (tenantId != null) requireCameraOwner(cameraId, tenantId);
        StreamSession session = tenantId == null ? manager.start(cameraId) : manager.start(cameraId, tenantId);
        return ResponseEntity.ok(toResponse(session, tenantId != null));
    }

    @PostMapping("/{cameraId}/stop")
    public ResponseEntity<Void> stop(@PathVariable UUID cameraId, @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Tenant-ID", required = false) UUID selectedTenant) {
        UUID tenantId = authorize(jwt, selectedTenant, "stream.manage");
        if (tenantId == null) manager.stop(cameraId);
        else {
            requireCameraOwner(cameraId, tenantId);
            manager.stop(cameraId, tenantId);
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/cameras")
    public Collection<CameraDto> cameras(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Tenant-ID", required = false) UUID selectedTenant) {
        UUID tenantId = authorize(jwt, selectedTenant, "camera.read");
        return tenantId == null ? manager.findAvailableCameras() : manager.findAvailableCameras(tenantId);
    }

    @GetMapping("/{cameraId}")
    public ResponseEntity<StreamResponse> get(@PathVariable UUID cameraId, @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Tenant-ID", required = false) UUID selectedTenant) {
        UUID tenantId = authorize(jwt, selectedTenant, "camera.read");
        if (tenantId != null) requireCameraOwner(cameraId, tenantId);
        StreamSession session = manager.find(cameraId);
        return session == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(toResponse(session, tenantId != null));
    }

    @GetMapping
    public Object findAll(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Tenant-ID", required = false) UUID selectedTenant) {
        UUID tenantId = authorize(jwt, selectedTenant, "camera.read");
        return tenantId == null ? manager.findAll() : manager.findAll(tenantId).stream()
                .map(session -> toResponse(session, true)).toList();
    }

    @GetMapping("/{cameraId}/url")
    public ResponseEntity<String> getHlsUrl(@PathVariable UUID cameraId, @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Tenant-ID", required = false) UUID selectedTenant) {
        UUID tenantId = authorize(jwt, selectedTenant, "camera.read");
        if (tenantId != null) requireCameraOwner(cameraId, tenantId);
        if (tenantId != null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        StreamSession session = manager.find(cameraId);
        return session == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(session.getHlsUrl());
    }

    @DeleteMapping("/{cameraId}")
    public ResponseEntity<Void> remove(@PathVariable UUID cameraId, @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Tenant-ID", required = false) UUID selectedTenant) {
        UUID tenantId = authorize(jwt, selectedTenant, "stream.manage");
        if (tenantId == null) manager.remove(cameraId);
        else {
            requireCameraOwner(cameraId, tenantId);
            manager.stop(cameraId, tenantId);
        }
        return ResponseEntity.noContent().build();
    }

    private UUID authorize(Jwt jwt, UUID selectedTenant, String permission) {
        if (!"oidc".equalsIgnoreCase(securityMode)) return null;
        if (jwt == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (selectedTenant == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-Tenant-ID is required");
        try {
            TenantContext context = tenantAuthorization.require(principal(jwt), selectedTenant, permission);
            return context.tenantId();
        } catch (TenantAuthorization.TenantMembershipRequiredException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        } catch (TenantAuthorization.TenantPermissionDeniedException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    private void requireCameraOwner(UUID cameraId, UUID tenantId) {
        try {
            cameraClient.findById(cameraId, tenantId);
        } catch (HttpClientErrorException.NotFound hiddenResource) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private static StreamResponse toResponse(StreamSession session, boolean tenantScoped) {
        return StreamResponse.builder().cameraId(session.getCameraId()).status(session.getStatus())
                .hlsUrl(tenantScoped ? null : session.getHlsUrl()).startedAt(session.getStartedAt())
                .reconnectCount(session.getReconnectCount())
                .lastError(tenantScoped ? null : session.getLastError()).build();
    }

    private static AuthenticatedPrincipal principal(Jwt jwt) {
        return new AuthenticatedPrincipal(jwt.getIssuer().toString(), jwt.getSubject(),
                jwt.getClaimAsString("email"), Boolean.TRUE.equals(jwt.getClaim("email_verified")),
                jwt.getClaimAsString("name"));
    }
}
