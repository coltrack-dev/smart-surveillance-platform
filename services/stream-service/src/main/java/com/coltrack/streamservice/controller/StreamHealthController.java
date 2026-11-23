package com.coltrack.streamservice.controller;

import com.coltrack.streamservice.model.StreamSession;
import com.coltrack.streamservice.model.StreamStatus;
import com.coltrack.streamservice.service.StreamManager;
import com.coltrack.streamservice.client.CameraClient;
import com.coltrack.identity.security.AuthenticatedPrincipal;
import com.coltrack.identity.security.TenantAuthorization;
import com.coltrack.identity.security.TenantContext;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;


@Slf4j
@RestController
@RequestMapping("/api/streams")
@RequiredArgsConstructor
public class StreamHealthController {


    private final StreamManager streamManager;
    private final CameraClient cameraClient;
    private final TenantAuthorization tenantAuthorization;
    @Value("${app.security.mode:legacy}")
    private String securityMode;


    /**
     * Returns stream health information.
     * <p>
     * Checks:
     * - session exists;
     * - FFmpeg process state;
     * - last received frame time.
     */
    @GetMapping("/{cameraId}/health")
    public Map<String, Object> health(
            @PathVariable UUID cameraId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Tenant-ID", required = false) UUID selectedTenant
    ) {

        if ("oidc".equalsIgnoreCase(securityMode)) {
            if (jwt == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
            if (selectedTenant == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-Tenant-ID is required");
            try {
                tenantAuthorization.require(new AuthenticatedPrincipal(jwt.getIssuer().toString(), jwt.getSubject(),
                        jwt.getClaimAsString("email"), Boolean.TRUE.equals(jwt.getClaim("email_verified")),
                        jwt.getClaimAsString("name")), selectedTenant, "camera.read");
                cameraClient.findById(cameraId, selectedTenant);
            } catch (TenantAuthorization.TenantMembershipRequiredException e) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            } catch (TenantAuthorization.TenantPermissionDeniedException e) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            }
        }

        log.info(
                "Checking stream health camera={}",
                cameraId
        );


        StreamSession session =
                streamManager.find(cameraId);


        if (session == null) {

            log.warn(
                    "Stream session not found camera={}",
                    cameraId
            );

            return Map.of(
                    "cameraId",
                    cameraId,
                    "status",
                    "NOT_FOUND",
                    "healthy",
                    false
            );
        }


        Process process =
                session.getFfmpegProcess();


        boolean ffmpegAlive =
                session.isAgentManaged()
                        ? session.getStatus() == StreamStatus.RUNNING
                        : process != null && process.isAlive();


        boolean framesAvailable =
                session.isAgentManaged()
                        ? session.getStatus() == StreamStatus.RUNNING
                        : session.getLastFrameTime() != null &&
                            Duration.between(
                                    session.getLastFrameTime(),
                                    Instant.now()
                            ).getSeconds() < 10;


        boolean healthy =
                session.getStatus() == StreamStatus.RUNNING
                        &&
                        ffmpegAlive
                        &&
                        framesAvailable;


        Map<String, Object> response = new LinkedHashMap<>();
        response.put("cameraId", cameraId);
        response.put("status", session.getStatus());
        response.put("ffmpegAlive", ffmpegAlive);
        // Remote agent status does not currently contain an actual frame timestamp.
        response.put("lastFrameTime", session.getLastFrameTime());
        response.put("reconnectCount", session.getReconnectCount());
        response.put("managedBy", session.isAgentManaged() ? "VIDEO_INGEST_AGENT" : "STREAM_SERVICE");
        response.put("healthy", healthy);
        return response;
    }
}
