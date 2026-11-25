package com.coltrack.streamservice.controller;

import com.coltrack.identity.security.AuthenticatedPrincipal;
import com.coltrack.identity.security.TenantAuthorization;
import com.coltrack.identity.security.TenantContext;
import com.coltrack.identity.quota.TenantQuotaGuard;
import com.coltrack.streamservice.config.BroadcastProperties;
import com.coltrack.streamservice.model.Broadcast;
import com.coltrack.streamservice.model.BroadcastVisibility;
import com.coltrack.streamservice.security.BroadcastRateLimiter;
import com.coltrack.streamservice.service.BroadcastService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.client.HttpClientErrorException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/broadcasts")
@RequiredArgsConstructor
public class BroadcastController {
    private final BroadcastService service;
    private final BroadcastProperties properties;
    private final BroadcastRateLimiter rateLimiter;
    private final TenantAuthorization tenantAuthorization;
    @Value("${app.security.mode:legacy}")
    private String securityMode;

    @PostMapping
    public ResponseEntity<Broadcast> create(@RequestHeader(value = "Authorization", required = false) String authorization,
                                            @RequestHeader(value = "X-Tenant-ID", required = false) UUID selectedTenant,
                                            @AuthenticationPrincipal Jwt jwt,
                                            @RequestBody CreateBroadcastRequest request) {
        TenantContext context = authorize(authorization, jwt, selectedTenant, "broadcast.manage");
        if (request == null || request.cameraId() == null) return ResponseEntity.badRequest().build();
        if (context == null) {
            Broadcast existing = service.findByCamera(request.cameraId()).orElse(null);
            Broadcast result = service.create(request.cameraId(), request.visibility() == null ? BroadcastVisibility.PRIVATE : request.visibility());
            return ResponseEntity.status(existing == null ? HttpStatus.CREATED : HttpStatus.OK).body(result);
        }
        Broadcast existing = service.findByCamera(request.cameraId(), context.tenantId()).orElse(null);
        Broadcast result = service.create(request.cameraId(), context.tenantId(),
                request.visibility() == null ? BroadcastVisibility.PRIVATE : request.visibility());
        return ResponseEntity.status(existing == null ? HttpStatus.CREATED : HttpStatus.OK).body(result);
    }

    @GetMapping
    public List<Broadcast> list(@RequestHeader(value = "Authorization", required = false) String authorization,
                                @RequestHeader(value = "X-Tenant-ID", required = false) UUID selectedTenant,
                                @AuthenticationPrincipal Jwt jwt) {
        TenantContext context = authorize(authorization, jwt, selectedTenant, "broadcast.read");
        return context == null ? service.list() : service.list(context.tenantId());
    }

    @GetMapping("/usage")
    public ResponseEntity<?> usage(@RequestHeader(value = "Authorization", required = false) String authorization,
                                   @RequestHeader(value = "X-Tenant-ID", required = false) UUID selectedTenant,
                                   @AuthenticationPrincipal Jwt jwt) {
        TenantContext context = authorize(authorization, jwt, selectedTenant, "broadcast.read");
        if (context == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.usage(context.tenantId()));
    }

    @GetMapping("/viewer-usage")
    public ResponseEntity<?> viewerUsage(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @RequestHeader(value = "X-Tenant-ID", required = false) UUID selectedTenant,
                                         @AuthenticationPrincipal Jwt jwt) {
        TenantContext context = authorize(authorization, jwt, selectedTenant, "broadcast.read");
        if (context == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(service.viewerUsage(context.tenantId()));
    }

    @GetMapping("/{id}")
    public Broadcast get(@RequestHeader(value = "Authorization", required = false) String authorization,
                         @RequestHeader(value = "X-Tenant-ID", required = false) UUID selectedTenant,
                         @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        TenantContext context = authorize(authorization, jwt, selectedTenant, "broadcast.read");
        return context == null ? service.get(id) : service.get(id, context.tenantId());
    }

    @PutMapping("/{id}/enabled")
    public Broadcast enabled(@RequestHeader(value = "Authorization", required = false) String authorization,
                             @RequestHeader(value = "X-Tenant-ID", required = false) UUID selectedTenant,
                             @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                             @RequestBody EnabledRequest request) {
        TenantContext context = authorize(authorization, jwt, selectedTenant, "broadcast.manage");
        return context == null ? service.setEnabled(id, request.enabled())
                : service.setEnabled(id, context.tenantId(), request.enabled());
    }

    @PutMapping("/{id}/visibility")
    public Broadcast visibility(@RequestHeader(value = "Authorization", required = false) String authorization,
                                @RequestHeader(value = "X-Tenant-ID", required = false) UUID selectedTenant,
                                @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                @RequestBody VisibilityRequest request) {
        TenantContext context = authorize(authorization, jwt, selectedTenant, "broadcast.manage");
        if (request == null || request.visibility() == null) throw new IllegalArgumentException("visibility is required");
        return context == null ? service.setVisibility(id, request.visibility())
                : service.setVisibility(id, context.tenantId(), request.visibility());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestHeader(value = "Authorization", required = false) String authorization,
                       @RequestHeader(value = "X-Tenant-ID", required = false) UUID selectedTenant,
                       @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        TenantContext context = authorize(authorization, jwt, selectedTenant, "broadcast.manage");
        if (context == null) service.delete(id); else service.delete(id, context.tenantId());
    }

    @GetMapping("/{id}/playback")
    public BroadcastService.PlaybackInfo playbackInfo(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                      @RequestHeader(value = "X-Tenant-ID", required = false) UUID selectedTenant,
                                                      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        TenantContext context = authorize(authorization, jwt, selectedTenant, "broadcast.read");
        return context == null ? service.playbackInfo(id) : service.playbackInfo(id, context.tenantId());
    }

    private TenantContext authorize(String authorization, Jwt jwt, UUID selectedTenant, String permission) {
        if (!rateLimiter.allow("management", 60)) throw new RateLimitException();
        if (!"oidc".equalsIgnoreCase(securityMode)) {
            String expected = "Bearer " + properties.getManagementToken();
            if (authorization == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), authorization.getBytes(StandardCharsets.UTF_8))) {
                throw new UnauthorizedException();
            }
            return null;
        }
        if (jwt == null) throw new UnauthorizedException();
        if (selectedTenant == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-Tenant-ID is required");
        try {
            return tenantAuthorization.require(principal(jwt), selectedTenant, permission);
        } catch (TenantAuthorization.TenantMembershipRequiredException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        } catch (TenantAuthorization.TenantPermissionDeniedException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    private static AuthenticatedPrincipal principal(Jwt jwt) {
        return new AuthenticatedPrincipal(jwt.getIssuer().toString(), jwt.getSubject(),
                jwt.getClaimAsString("email"), Boolean.TRUE.equals(jwt.getClaim("email_verified")),
                jwt.getClaimAsString("name"));
    }

    public record CreateBroadcastRequest(UUID cameraId, BroadcastVisibility visibility) {}
    public record EnabledRequest(boolean enabled) {}
    public record VisibilityRequest(BroadcastVisibility visibility) {}
    public record ErrorResponse(String error) {}
    private static class UnauthorizedException extends RuntimeException {}
    private static class RateLimitException extends RuntimeException {}

    @ExceptionHandler(BroadcastService.BroadcastNotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(RuntimeException error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("Not found"));
    }
    @ExceptionHandler(HttpClientErrorException.NotFound.class)
    public ResponseEntity<ErrorResponse> cameraNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("Not found"));
    }
    @ExceptionHandler(BroadcastService.BroadcastOfflineException.class)
    public ResponseEntity<ErrorResponse> offline(RuntimeException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(error.getMessage()));
    }
    @ExceptionHandler(BroadcastService.PrivateBroadcastException.class)
    public ResponseEntity<ErrorResponse> privateBroadcast() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("Authentication required"));
    }
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ErrorResponse> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("Unauthorized"));
    }
    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<ErrorResponse> limited() {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(new ErrorResponse("Too many requests"));
    }
    @ExceptionHandler(TenantQuotaGuard.QuotaExceededException.class)
    public ResponseEntity<QuotaError> quotaExceeded(TenantQuotaGuard.QuotaExceededException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new QuotaError(error.code(), "MAX_ACTIVE_BROADCASTS", error.used(), error.limit()));
    }
    @ExceptionHandler(TenantQuotaGuard.QuotaAuthorityUnavailableException.class)
    public ResponseEntity<QuotaError> quotaUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new QuotaError("QUOTA_AUTHORITY_UNAVAILABLE", null, null, null));
    }
    public record QuotaError(String code, String resource, Long currentUsage, Integer limit) {}
}
