package com.coltrack.streamservice.service;

import com.coltrack.streamservice.config.BroadcastProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.Optional;

@Service
public class PlaybackTokenService {
    private final BroadcastProperties properties;

    public PlaybackTokenService(BroadcastProperties properties) {
        this.properties = properties;
    }

    public String issue(UUID broadcastId, long playbackVersion, Instant expiresAt) {
        return issue(broadcastId, playbackVersion, expiresAt, null, null);
    }

    public String issue(UUID broadcastId, long playbackVersion, Instant expiresAt, UUID tenantId, UUID userId) {
        String payload = broadcastId + "." + playbackVersion + "." + expiresAt.getEpochSecond() + "."
                + (tenantId == null ? "" : tenantId) + "." + (userId == null ? "" : userId);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload));
    }

    public boolean isValid(String token, UUID expectedId, long expectedVersion, Instant now) {
        return verify(token, now).filter(grant -> grant.broadcastId().equals(expectedId)
                && grant.playbackVersion() == expectedVersion).isPresent();
    }

    public Optional<Grant> verify(String token, Instant now) {
        if (token == null || token.length() > 512) return Optional.empty();
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2) return Optional.empty();
            String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            String[] fields = payload.split("\\.", -1);
            if (fields.length != 5 && fields.length != 3) return Optional.empty();
            long expiresAt = Long.parseLong(fields[2]);
            if (expiresAt <= now.getEpochSecond()) return Optional.empty();
            byte[] signature = Base64.getUrlDecoder().decode(parts[1]);
            if (!MessageDigest.isEqual(sign(payload), signature)) return Optional.empty();
            UUID tenantId = fields.length == 5 && !fields[3].isBlank() ? UUID.fromString(fields[3]) : null;
            UUID userId = fields.length == 5 && !fields[4].isBlank() ? UUID.fromString(fields[4]) : null;
            return Optional.of(new Grant(UUID.fromString(fields[0]), Long.parseLong(fields[1]),
                    Instant.ofEpochSecond(expiresAt), tenantId, userId));
        } catch (IllegalArgumentException error) {
            return Optional.empty();
        }
    }

    private byte[] sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getPlaybackSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new IllegalStateException("Could not sign playback token", error);
        }
    }

    public record Grant(UUID broadcastId, long playbackVersion, Instant expiresAt, UUID tenantId, UUID userId) {}
}
