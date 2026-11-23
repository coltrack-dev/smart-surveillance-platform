package com.coltrack.streamservice.controller;

import com.coltrack.streamservice.config.BroadcastProperties;
import com.coltrack.streamservice.security.BroadcastRateLimiter;
import com.coltrack.streamservice.service.BroadcastService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequiredArgsConstructor
public class BroadcastPlaybackController {
    private static final Pattern URI_ATTRIBUTE = Pattern.compile("URI=\"([^\"]+)\"");
    private final Semaphore playbackSlots = new Semaphore(32);
    private final BroadcastService broadcastService;
    private final BroadcastProperties properties;
    private final BroadcastRateLimiter rateLimiter;
    private final RestClient.Builder restClientBuilder;

    @GetMapping("/api/playback/{publicId}/token")
    public BroadcastService.PlaybackToken issueToken(@PathVariable UUID publicId, HttpServletRequest request) {
        checkRateLimit("token:", request);
        return broadcastService.playbackToken(publicId);
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
                    .body(byte[].class);
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
            } else if (line.contains("URI=")) {
                Matcher matcher = URI_ATTRIBUTE.matcher(line);
                StringBuffer rewritten = new StringBuffer();
                boolean foundUri = false;
                while (matcher.find()) {
                    foundUri = true;
                    matcher.appendReplacement(rewritten, Matcher.quoteReplacement("URI=\"" + playbackPath(publicId, safeReference(matcher.group(1)), token) + "\""));
                }
                if (!foundUri) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid HLS URI attribute");
                matcher.appendTail(rewritten);
                line = rewritten.toString();
            }
            result.append(line).append('\n');
        }
        return result.toString();
    }

    private static String safeReference(String value) {
        if (value.isBlank() || value.contains(":") || value.contains("/") || value.contains("\\") || value.contains("..") || value.contains("?")) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid HLS reference");
        }
        return value;
    }

    private static String playbackPath(UUID publicId, String file, String token) {
        return "/broadcast-play/" + publicId + "/" + file + "?token=" + token;
    }

    private static boolean safeFileName(String value) {
        return value.matches("[A-Za-z0-9._-]{1,180}") && !value.contains("..") && !value.equals(".");
    }

    private static String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
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
}
