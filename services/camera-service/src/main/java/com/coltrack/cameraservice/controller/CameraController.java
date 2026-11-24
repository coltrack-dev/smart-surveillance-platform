package com.coltrack.cameraservice.controller;

import com.coltrack.cameraservice.dto.CameraResourceResponse;
import com.coltrack.cameraservice.dto.CreateCameraRequest;
import com.coltrack.cameraservice.entity.CameraEntity;
import com.coltrack.cameraservice.service.CameraMonitoringService;
import com.coltrack.cameraservice.service.CameraService;
import com.coltrack.identity.security.AuthenticatedPrincipal;
import com.coltrack.identity.security.TenantAuthorization;
import com.coltrack.identity.security.TenantContext;
import com.coltrack.identity.quota.TenantQuotaGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/cameras")
@RequiredArgsConstructor
public class CameraController {
    private final CameraService service;
    private final CameraMonitoringService monitoringService;
    private final TenantAuthorization tenantAuthorization;
    @Value("${app.security.mode:legacy}")
    private String securityMode;

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateCameraRequest request,
                                    @AuthenticationPrincipal Jwt jwt,
                                    @RequestHeader(value = "X-Tenant-ID", required = false) UUID selectedTenant,
                                    @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        UUID tenantId = authorize(jwt, selectedTenant, "camera.manage");
        if (tenantId != null && (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key is required for Streaming camera creation");
        CameraEntity camera = service.create(request.name(), request.lbsLocationId(), request.rtspUrl(),
                request.rtspUsername(), request.rtspPassword(), request.rtspUrlFormat(),
                request.videoProcessingMode(), request.autoStart(), tenantId, idempotencyKey);
        return ResponseEntity.created(URI.create("/api/cameras/" + camera.getId())).body(present(camera));
    }

    @GetMapping
    public Object findAll(@PageableDefault(size = 20, sort = "cameraNumber", direction = Sort.Direction.ASC) Pageable pageable,
                          @AuthenticationPrincipal Jwt jwt,
                          @RequestHeader(value = "X-Tenant-ID", required = false) UUID selectedTenant) {
        UUID tenantId = authorize(jwt, selectedTenant, "camera.read");
        Page<CameraEntity> page = tenantId == null ? service.findAll(pageable) : service.findAll(tenantId, pageable);
        return "oidc".equalsIgnoreCase(securityMode) ? page.map(CameraResourceResponse::from) : page;
    }

    @GetMapping("/usage")
    public ResponseEntity<?> usage(@AuthenticationPrincipal Jwt jwt,
                                   @RequestHeader(value = "X-Tenant-ID", required = false) UUID selectedTenant) {
        UUID tenantId = authorize(jwt, selectedTenant, "camera.read");
        if (tenantId == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.usage(tenantId));
    }

    @GetMapping("/{id}")
    public Object findById(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
                           @RequestHeader(value = "X-Tenant-ID", required = false) UUID selectedTenant) {
        UUID tenantId = authorize(jwt, selectedTenant, "camera.read");
        return present(tenantId == null ? service.findById(id) : service.findById(id, tenantId));
    }

    @PutMapping("/{id}")
    public Object update(@PathVariable UUID id, @RequestBody CreateCameraRequest request,
                         @AuthenticationPrincipal Jwt jwt,
                         @RequestHeader(value = "X-Tenant-ID", required = false) UUID selectedTenant) {
        UUID tenantId = authorize(jwt, selectedTenant, "camera.manage");
        return present(service.update(id, request.name(), request.lbsLocationId(), request.rtspUrl(),
                request.rtspUsername(), request.rtspPassword(), request.rtspUrlFormat(),
                request.videoProcessingMode(), tenantId));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
                       @RequestHeader(value = "X-Tenant-ID", required = false) UUID selectedTenant) {
        UUID tenantId = authorize(jwt, selectedTenant, "camera.manage");
        if (tenantId == null) service.delete(id); else service.delete(id, tenantId);
    }

    @PostMapping("/{id}/heartbeat")
    public Object heartbeat(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
                            @RequestHeader(value = "X-Tenant-ID", required = false) UUID selectedTenant) {
        UUID tenantId = authorize(jwt, selectedTenant, "camera.manage");
        return present(tenantId == null ? service.heartbeat(id) : service.heartbeat(id, tenantId));
    }

    @PostMapping("/{id}/check-stream")
    public Object checkStream(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
                              @RequestHeader(value = "X-Tenant-ID", required = false) UUID selectedTenant) {
        UUID tenantId = authorize(jwt, selectedTenant, "camera.manage");
        return present(tenantId == null ? monitoringService.checkCamera(id) : monitoringService.checkCamera(id, tenantId));
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

    private Object present(CameraEntity camera) {
        return "oidc".equalsIgnoreCase(securityMode) ? CameraResourceResponse.from(camera) : camera;
    }

    @ExceptionHandler(TenantQuotaGuard.QuotaExceededException.class)
    public ResponseEntity<QuotaError> quotaExceeded(TenantQuotaGuard.QuotaExceededException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new QuotaError(error.code(), "MAX_CAMERAS", error.used(), error.limit()));
    }

    @ExceptionHandler(TenantQuotaGuard.QuotaAuthorityUnavailableException.class)
    public ResponseEntity<QuotaError> quotaUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new QuotaError("QUOTA_AUTHORITY_UNAVAILABLE", null, null, null));
    }

    @ExceptionHandler(CameraService.IdempotencyConflictException.class)
    public ResponseEntity<QuotaError> idempotencyConflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new QuotaError("IDEMPOTENCY_KEY_REUSED", null, null, null));
    }

    public record QuotaError(String code, String resource, Long currentUsage, Integer limit) {}

    private static AuthenticatedPrincipal principal(Jwt jwt) {
        return new AuthenticatedPrincipal(jwt.getIssuer().toString(), jwt.getSubject(),
                jwt.getClaimAsString("email"), Boolean.TRUE.equals(jwt.getClaim("email_verified")),
                jwt.getClaimAsString("name"));
    }
}
