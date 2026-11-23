package com.coltrack.streamservice.controller;

import com.coltrack.streamservice.config.BroadcastProperties;
import com.coltrack.streamservice.model.Broadcast;
import com.coltrack.streamservice.repository.BroadcastRepository;
import com.coltrack.streamservice.security.BroadcastRateLimiter;
import com.coltrack.streamservice.service.PlaybackTokenService;
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
    private final BroadcastRepository repository;
    private final PlaybackTokenService tokens;
    private final BroadcastProperties properties;
    private final BroadcastRateLimiter rateLimiter;
    private final RestClient.Builder restClientBuilder;

    @GetMapping("/broadcast-play/{id}/{fileName}")
    public ResponseEntity<byte[]> file(@PathVariable UUID id, @PathVariable String fileName,
                                       @RequestParam String token, HttpServletRequest request) {
        String remote = request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
        if (!rateLimiter.allow("playback:" + remote, properties.getRateLimitPerMinute())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        }
        Broadcast broadcast = repository.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!broadcast.enabled() || !tokens.isValid(token, id, broadcast.playbackVersion(), Instant.now())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
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
            body = rewritePlaylist(new String(body, StandardCharsets.UTF_8), id, token).getBytes(StandardCharsets.UTF_8);
        }
        return ResponseEntity.ok()
                .contentType(contentType)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }

    static String rewritePlaylist(String playlist, UUID id, String token) {
        String[] lines = playlist.split("\\R", -1);
        StringBuilder result = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                line = playbackPath(id, safeReference(trimmed), token);
            } else if (line.contains("URI=")) {
                Matcher matcher = URI_ATTRIBUTE.matcher(line);
                StringBuffer rewritten = new StringBuffer();
                boolean foundUri = false;
                while (matcher.find()) {
                    foundUri = true;
                    matcher.appendReplacement(rewritten, Matcher.quoteReplacement("URI=\"" + playbackPath(id, safeReference(matcher.group(1)), token) + "\""));
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

    private static String playbackPath(UUID id, String file, String token) {
        return "/broadcast-play/" + id + "/" + file + "?token=" + token;
    }

    private static boolean safeFileName(String value) {
        return value.matches("[A-Za-z0-9._-]{1,180}") && !value.contains("..") && !value.equals(".");
    }

    private static String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
