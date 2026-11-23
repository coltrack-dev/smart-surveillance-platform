package com.coltrack.identity.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class TenantWorkloadJwtTest {
    @Test
    void signedWorkloadTokenCarriesValidatedTenantContext() {
        byte[] secret = new byte[32];
        String token = TenantWorkloadJwt.issue(TenantWorkloadJwt.encoder(secret), UUID.randomUUID(), Instant.now());
        var jwt = TenantWorkloadJwt.decoder(secret).decode(token);
        assertEquals(TenantWorkloadJwt.ISSUER, jwt.getIssuer().toString());
        assertEquals(TenantWorkloadJwt.AUDIENCE, jwt.getAudience().getFirst());
        assertDoesNotThrow(() -> UUID.fromString(jwt.getClaimAsString(TenantWorkloadJwt.TENANT_CLAIM)));
    }

    @Test
    void decoderRejectsModifiedSignatureAndMissingTenant() {
        byte[] secret = new byte[32];
        String token = TenantWorkloadJwt.issue(TenantWorkloadJwt.encoder(secret), UUID.randomUUID(), Instant.now());
        String[] parts = token.split("\\.");
        parts[2] = (parts[2].charAt(0) == 'A' ? "B" : "A") + parts[2].substring(1);
        assertThrows(JwtException.class, () -> TenantWorkloadJwt.decoder(secret).decode(String.join(".", parts)));
        assertThrows(IllegalStateException.class, () -> TenantWorkloadJwt.decodeSecret(Base64.getEncoder().encodeToString(new byte[16])));
    }

    @Test
    void ownershipLookupTokenHasNarrowScopeAndNoTenantClaim() {
        byte[] secret = new byte[32];
        var encoder = TenantWorkloadJwt.encoder(secret);
        String token = TenantWorkloadJwt.issueOwnershipLookup(encoder, Instant.now());

        var ownershipJwt = TenantWorkloadJwt.ownershipLookupDecoder(secret).decode(token);
        assertEquals(TenantWorkloadJwt.OWNERSHIP_LOOKUP_SCOPE,
                ownershipJwt.getClaimAsString(TenantWorkloadJwt.SCOPE_CLAIM));
        assertNull(ownershipJwt.getClaim(TenantWorkloadJwt.TENANT_CLAIM));
        assertThrows(JwtException.class, () -> TenantWorkloadJwt.decoder(secret).decode(token));
    }
}
