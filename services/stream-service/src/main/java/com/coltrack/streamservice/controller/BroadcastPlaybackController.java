package com.coltrack.streamservice.controller;

import com.coltrack.streamservice.config.BroadcastProperties;
import com.coltrack.streamservice.security.BroadcastRateLimiter;
import com.coltrack.streamservice.service.BroadcastService;
import com.coltrack.streamservice.service.ViewerSessionService;
import com.coltrack.identity.security.AuthenticatedPrincipal;
import com.coltrack.identity.security.TenantAuthorization;
import com.coltrack.identity.security.TenantContext;
import com.coltrack.identity.quota.TenantQuotaGuard;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.beans.factory.annotation.Value;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
public class BroadcastPlaybackController {
    private static final Pattern URI_ATTRIBUTE = Pattern.compile("(?i)(?<![A-Za-z0-9_-])URI\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern URI_ATTRIBUTE_MARKER = Pattern.compile("(?i)(?<![A-Za-z0-9_-])URI\\s*=");
    private static final Pattern SAFE_REFERENCE = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]{0,179}");
    private final Semaphore playbackSlots = new Semaphore(32);
    private final BroadcastService broadcastService;
    private final BroadcastProperties properties;
    private final BroadcastRateLimiter rateLimiter;
    private final RestClient.Builder restClientBuilder;
    private final TenantAuthorization tenantAuthorization;
    @Value("${app.security.mode:legacy}")
    private String securityMode;

    public BroadcastPlaybackController(BroadcastService broadcastService, BroadcastProperties properties,
                                       BroadcastRateLimiter rateLimiter,
                                       @Qualifier("broadcastPlaybackRestClientBuilder") RestClient.Builder restClientBuilder,
                                       TenantAuthorization tenantAuthorization) {
        this.broadcastService = broadcastService;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.restClientBuilder = restClientBuilder;
        this.tenantAuthorization = tenantAuthorization;
    }

    @GetMapping("/api/playback/{publicId}/token")
    public BroadcastService.PlaybackToken issueToken(@PathVariable UUID publicId, HttpServletRequest request,
                                                     @AuthenticationPrincipal Jwt jwt,
                                                     @RequestHeader(value = "X-Tenant-ID", required = false) UUID selectedTenant) {
        checkRateLimit("token:", request);
        if ("oidc".equalsIgnoreCase(securityMode))
            throw new ResponseStatusException(HttpStatus.GONE, "Use persistent viewer session admission");
        if (jwt == null || selectedTenant == null) return broadcastService.playbackToken(publicId);
        try {
            TenantContext context = tenantAuthorization.require(principal(jwt), selectedTenant, "playback.view");
            return broadcastService.playbackToken(publicId, context);
        } catch (TenantAuthorization.TenantMembershipRequiredException | TenantAuthorization.TenantPermissionDeniedException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
    }

    @PostMapping("/api/playback/{publicId}/session")
    public Object admitSession(@PathVariable UUID publicId, @RequestBody(required = false) SessionRequest body,
                               HttpServletRequest request, @AuthenticationPrincipal Jwt jwt,
                               @RequestHeader(value = "X-Tenant-ID", required = false) UUID selectedTenant) {
        checkRateLimit("session:", request);
        if (!"oidc".equalsIgnoreCase(securityMode)) {
            BroadcastService.PlaybackToken token = broadcastService.playbackToken(publicId);
            return new LegacySession(token.expiresAt(), token.token(), token.playlistUrl());
        }
        if (body == null || body.credential() == null)
            throw new ViewerSessionService.InvalidSessionCredentialException();
        AuthenticatedPrincipal viewer = jwt == null ? null : principal(jwt);
        return broadcastService.playbackSession(publicId, body.credential(), viewer, selectedTenant);
    }

    @DeleteMapping("/api/playback/{publicId}/session")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void releaseSession(@PathVariable UUID publicId, @RequestBody(required = false) SessionRequest body,
                               HttpServletRequest request) {
        checkRateLimit("session-release:", request);
        if (body != null && body.credential() != null)
            broadcastService.releasePlaybackSession(publicId, body.credential());
    }

    @GetMapping("/broadcast-play/{publicId}/{fileName}")
    public ResponseEntity<byte[]> file(@PathVariable UUID publicId, @PathVariable String fileName,
                                       @RequestParam String token, HttpServletRequest request) {
        checkRateLimit("media:", request);
        var broadcast = broadcastService.authorizePlayback(publicId, token, Instant.now());
        if (!safeFileName(fileName)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (!playbackSlots.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);

        byte[] body;
        try {
            body = restClientBuilder
                    .baseUrl(trimSlash(properties.getMediaHlsBaseUrl()))
                    .build()
                    .get()
                    .uri("/{cameraId}/{fileName}", broadcast.cameraId(), fileName)
                    .headers(headers -> headers.setBasicAuth(properties.getMediaReadUsername(), properties.getMediaReadPassword()))
                    .retrieve()
                    .onStatus(HttpStatusCode::is3xxRedirection, (upstreamRequest, response) -> {
                        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                                "HLS upstream redirects are not allowed");
                    })
                    .body(byte[].class);
        } catch (ResponseStatusException error) {
            throw error;
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Live media is temporarily unavailable");
        } finally {
            playbackSlots.release();
        }
        if (body == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);

        MediaType contentType = fileName.endsWith(".m3u8")
                ? MediaType.parseMediaType("application/vnd.apple.mpegurl")
                : fileName.endsWith(".ts") ? MediaType.parseMediaType("video/mp2t")
                : MediaType.APPLICATION_OCTET_STREAM;
        if (fileName.endsWith(".m3u8")) {
            body = rewritePlaylist(new String(body, StandardCharsets.UTF_8), publicId, token).getBytes(StandardCharsets.UTF_8);
        }
        return ResponseEntity.ok()
                .contentType(contentType)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer")
                .body(body);
    }

    private void checkRateLimit(String keyPrefix, HttpServletRequest request) {
        String remote = request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
        if (!rateLimiter.allow(keyPrefix + remote, properties.getRateLimitPerMinute())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    static String rewritePlaylist(String playlist, UUID publicId, String token) {
        String[] lines = playlist.split("\\R", -1);
        StringBuilder result = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                line = playbackPath(publicId, safeReference(trimmed), token);
            } else if (line.startsWith("#") && URI_ATTRIBUTE_MARKER.matcher(line).find()) {
                Matcher matcher = URI_ATTRIBUTE.matcher(line);
                StringBuffer rewritten = new StringBuffer();
                int rewrittenReferences = 0;
                while (matcher.find()) {
                    rewrittenReferences++;
                    matcher.appendReplacement(rewritten, Matcher.quoteReplacement("URI=\"" + playbackPath(publicId, safeReference(matcher.group(1)), token) + "\""));
                }
                Matcher markers = URI_ATTRIBUTE_MARKER.matcher(line);
                int declaredReferences = 0;
                while (markers.find()) declaredReferences++;
                if (rewrittenReferences == 0 || rewrittenReferences != declaredReferences) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid HLS URI attribute");
                }
                matcher.appendTail(rewritten);
                line = rewritten.toString();
            }
            result.append(line).append('\n');
        }
        return result.toString();
    }

    private static String safeReference(String value) {
        if (value == null || !SAFE_REFERENCE.matcher(value).matches() || value.contains("..")) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid HLS reference");
        }
        return value;
    }

    private static String playbackPath(UUID publicId, String file, String token) {
        return "/broadcast-play/" + publicId + "/" + file + "?token=" + token;
    }

    private static boolean safeFileName(String value) {
        return SAFE_REFERENCE.matcher(value).matches() && !value.contains("..") && !value.equals(".");
    }

    private static String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static AuthenticatedPrincipal principal(Jwt jwt) {
        return new AuthenticatedPrincipal(jwt.getIssuer().toString(), jwt.getSubject(),
                jwt.getClaimAsString("email"), Boolean.TRUE.equals(jwt.getClaim("email_verified")),
                jwt.getClaimAsString("name"));
    }

    @ExceptionHandler(BroadcastService.PrivateBroadcastException.class)
    public ResponseEntity<String> privateBroadcast() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Authentication required");
    }

    @ExceptionHandler(BroadcastService.BroadcastOfflineException.class)
    public ResponseEntity<String> offline() {
        return ResponseEntity.status(HttpStatus.GONE).body("Broadcast is unavailable");
    }

    @ExceptionHandler(BroadcastService.BroadcastNotFoundException.class)
    public ResponseEntity<String> notFound() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(TenantQuotaGuard.QuotaExceededException.class)
    public ResponseEntity<ViewerQuotaError> viewerQuotaExceeded(TenantQuotaGuard.QuotaExceededException error) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(new ViewerQuotaError(
                error.code(), "MAX_CONCURRENT_VIEWERS", error.used(), error.limit()));
    }

    @ExceptionHandler(TenantQuotaGuard.QuotaAuthorityUnavailableException.class)
    public ResponseEntity<ViewerQuotaError> viewerQuotaUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ViewerQuotaError("QUOTA_AUTHORITY_UNAVAILABLE", "MAX_CONCURRENT_VIEWERS", null, null));
    }

    @ExceptionHandler(ViewerSessionService.InvalidSessionCredentialException.class)
    public ResponseEntity<String> invalidViewerSession() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Playback session is invalid");
    }

    @ExceptionHandler(ViewerSessionService.SessionCredentialConflictException.class)
    public ResponseEntity<ViewerQuotaError> viewerSessionConflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ViewerQuotaError("PLAYBACK_SESSION_INVALID", null, null, null));
    }

    public record SessionRequest(String credential) {}
    public record LegacySession(Instant expiresAt, String token, String playlistUrl) {}
    public record ViewerQuotaError(String code, String resource, Long currentUsage, Integer limit) {}
}
