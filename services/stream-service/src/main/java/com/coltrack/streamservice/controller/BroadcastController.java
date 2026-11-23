package com.coltrack.streamservice.controller;

import com.coltrack.streamservice.config.BroadcastProperties;
import com.coltrack.streamservice.model.Broadcast;
import com.coltrack.streamservice.model.BroadcastVisibility;
import com.coltrack.streamservice.security.BroadcastRateLimiter;
import com.coltrack.streamservice.service.BroadcastService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

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
    @Value("${app.security.mode:legacy}")
    private String securityMode;

    @PostMapping
    public ResponseEntity<Broadcast> create(@RequestHeader(value = "Authorization", required = false) String authorization,
                                            @RequestBody CreateBroadcastRequest request) {
        authorize(authorization);
        if (request == null || request.cameraId() == null) {
            return ResponseEntity.badRequest().build();
        }
        Broadcast existing = service.findByCamera(request.cameraId()).orElse(null);
        Broadcast broadcast = service.create(request.cameraId(), request.visibility() == null ? BroadcastVisibility.PRIVATE : request.visibility());
        return ResponseEntity.status(existing == null ? HttpStatus.CREATED : HttpStatus.OK).body(broadcast);
    }

    @GetMapping
    public List<Broadcast> list(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authorize(authorization);
        return service.list();
    }

    @GetMapping("/{id}")
    public Broadcast get(@RequestHeader(value = "Authorization", required = false) String authorization,
                         @PathVariable UUID id) {
        authorize(authorization);
        return service.get(id);
    }

    @PutMapping("/{id}/enabled")
    public Broadcast enabled(@RequestHeader(value = "Authorization", required = false) String authorization,
                             @PathVariable UUID id, @RequestBody EnabledRequest request) {
        authorize(authorization);
        return service.setEnabled(id, request.enabled());
    }

    @PutMapping("/{id}/visibility")
    public Broadcast visibility(@RequestHeader(value = "Authorization", required = false) String authorization,
                               @PathVariable UUID id, @RequestBody VisibilityRequest request) {
        authorize(authorization);
        if (request == null || request.visibility() == null) throw new IllegalArgumentException("visibility is required");
        return service.setVisibility(id, request.visibility());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestHeader(value = "Authorization", required = false) String authorization,
                       @PathVariable UUID id) {
        authorize(authorization);
        service.delete(id);
    }

    @GetMapping("/{id}/playback")
    public BroadcastService.PlaybackInfo playbackInfo(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                      @PathVariable UUID id) {
        authorize(authorization);
        return service.playbackInfo(id);
    }

    private void authorize(String authorization) {
        if (!rateLimiter.allow("management", 60)) throw new RateLimitException();
        if ("oidc".equalsIgnoreCase(securityMode)) return;
        String expected = "Bearer " + properties.getManagementToken();
        if (authorization == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), authorization.getBytes(StandardCharsets.UTF_8))) {
            throw new UnauthorizedException();
        }
    }

    public record CreateBroadcastRequest(UUID cameraId, BroadcastVisibility visibility) {}
    public record EnabledRequest(boolean enabled) {}
    public record VisibilityRequest(BroadcastVisibility visibility) {}

    @ExceptionHandler(BroadcastService.BroadcastNotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(RuntimeException error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(error.getMessage()));
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

    public record ErrorResponse(String error) {}
    private static class UnauthorizedException extends RuntimeException {}
    private static class RateLimitException extends RuntimeException {}
}
