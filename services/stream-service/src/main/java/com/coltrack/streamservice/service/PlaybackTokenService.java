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

@Service
public class PlaybackTokenService {
    private final BroadcastProperties properties;

    public PlaybackTokenService(BroadcastProperties properties) {
        this.properties = properties;
    }

    public String issue(UUID broadcastId, long playbackVersion, Instant expiresAt) {
        String payload = broadcastId + "." + playbackVersion + "." + expiresAt.getEpochSecond();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload));
    }

    public boolean isValid(String token, UUID expectedId, long expectedVersion, Instant now) {
        if (token == null || token.length() > 512) return false;
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2) return false;
            String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            String[] fields = payload.split("\\.", -1);
            if (fields.length != 3 || !UUID.fromString(fields[0]).equals(expectedId)
                    || Long.parseLong(fields[1]) != expectedVersion) return false;
            long expiresAt = Long.parseLong(fields[2]);
            if (expiresAt <= now.getEpochSecond()) return false;
            byte[] signature = Base64.getUrlDecoder().decode(parts[1]);
            return MessageDigest.isEqual(sign(payload), signature);
        } catch (IllegalArgumentException error) {
            return false;
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
}
