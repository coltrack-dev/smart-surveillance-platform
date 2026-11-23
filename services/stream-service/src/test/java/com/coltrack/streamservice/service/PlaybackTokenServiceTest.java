package com.coltrack.streamservice.service;

import com.coltrack.streamservice.config.BroadcastProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlaybackTokenServiceTest {
    private final BroadcastProperties properties = new BroadcastProperties();
    private final PlaybackTokenService service;

    PlaybackTokenServiceTest() {
        properties.setPlaybackSecret("test-secret-that-is-long-enough-for-hmac");
        service = new PlaybackTokenService(properties);
    }

    @Test
    void acceptsOnlyMatchingUnexpiredBroadcastToken() {
        UUID id = UUID.randomUUID();
        Instant expires = Instant.now().plusSeconds(120);
        String token = service.issue(id, 3, expires);

        assertTrue(service.isValid(token, id, 3, Instant.now()));
        assertFalse(service.isValid(token, id, 4, Instant.now()));
        assertFalse(service.isValid(token, UUID.randomUUID(), 3, Instant.now()));
        assertFalse(service.isValid(token, id, 3, expires));
        assertFalse(service.isValid(token + "x", id, 3, Instant.now()));
    }
}
