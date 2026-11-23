package com.coltrack.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OidcJwtValidatorsTest {
    private static final String ISSUER = "https://issuer.example/realms/streaming";

    @Test
    void acceptsTokenWithExpectedIssuerAudienceAndLifetime() {
        assertFalse(OidcJwtValidators.forIssuerAndAudience(ISSUER, "streaming-api")
                .validate(jwt(ISSUER, List.of("streaming-api"), Instant.now().plusSeconds(60)))
                .hasErrors());
    }

    @Test
    void rejectsWrongIssuer() {
        assertTrue(OidcJwtValidators.forIssuerAndAudience(ISSUER, "streaming-api")
                .validate(jwt("https://attacker.example", List.of("streaming-api"),
                        Instant.now().plusSeconds(60)))
                .hasErrors());
    }

    @Test
    void rejectsMissingAudience() {
        assertTrue(OidcJwtValidators.forIssuerAndAudience(ISSUER, "streaming-api")
                .validate(jwt(ISSUER, List.of("other-api"), Instant.now().plusSeconds(60)))
                .hasErrors());
    }

    @Test
    void rejectsExpiredToken() {
        Instant now = Instant.now();
        Jwt expired = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .issuer(ISSUER)
                .subject("user-1")
                .issuedAt(now.minusSeconds(120))
                .expiresAt(now.minusSeconds(60))
                .audience(List.of("streaming-api"))
                .build();

        assertTrue(OidcJwtValidators.forIssuerAndAudience(ISSUER, "streaming-api")
                .validate(expired).hasErrors());
    }

    @Test
    void rejectsTokenBeforeNotBeforeTime() {
        Instant now = Instant.now();
        Jwt notYetValid = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .issuer(ISSUER)
                .subject("user-1")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(120))
                .notBefore(now.plusSeconds(300))
                .audience(List.of("streaming-api"))
                .build();

        assertTrue(OidcJwtValidators.forIssuerAndAudience(ISSUER, "streaming-api")
                .validate(notYetValid).hasErrors());
    }

    private static Jwt jwt(String issuer, List<String> audience, Instant expiresAt) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .issuer(issuer)
                .subject("user-1")
                .issuedAt(now.minusSeconds(1))
                .expiresAt(expiresAt)
                .audience(audience)
                .build();
    }
}
