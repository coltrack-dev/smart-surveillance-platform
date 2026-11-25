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

    @Test
    void sessionGrantBindsTenantUserAndSession() {
        UUID broadcast = UUID.randomUUID(), tenant = UUID.randomUUID(), user = UUID.randomUUID(), session = UUID.randomUUID();
        Instant expires = Instant.now().plusSeconds(120);
        String token = service.issue(broadcast, 8, expires, tenant, user, session);

        PlaybackTokenService.Grant grant = service.verify(token, Instant.now()).orElseThrow();
        assertEquals(broadcast, grant.broadcastId());
        assertEquals(tenant, grant.tenantId());
        assertEquals(user, grant.userId());
        assertEquals(session, grant.sessionId());
    }
}
