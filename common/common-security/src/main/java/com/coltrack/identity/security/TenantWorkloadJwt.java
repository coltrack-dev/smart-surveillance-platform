package com.coltrack.identity.security;

import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.JwsHeader;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** Short-lived signed service delegation carrying a trusted tenant context to Camera service. */
public final class TenantWorkloadJwt {
    public static final String ISSUER = "https://stream-service.internal";
    public static final String AUDIENCE = "camera-service";
    public static final String TENANT_CLAIM = "tenant_id";
    public static final String SCOPE_CLAIM = "scope";
    public static final String OWNERSHIP_LOOKUP_SCOPE = "camera.ownership.read";

    private TenantWorkloadJwt() {}

    public static byte[] decodeSecret(String base64Secret) {
        if (base64Secret == null || base64Secret.isBlank()) {
            throw new IllegalStateException("STREAMING_INTERNAL_JWT_SECRET is required in OIDC mode");
        }
        byte[] secret;
        try {
            secret = Base64.getDecoder().decode(base64Secret);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("STREAMING_INTERNAL_JWT_SECRET must be base64 encoded", exception);
        }
        if (secret.length < 32) throw new IllegalStateException("STREAMING_INTERNAL_JWT_SECRET must contain at least 32 bytes");
        return secret;
    }

    public static JwtEncoder encoder(byte[] secret) {
        SecretKey key = new SecretKeySpec(secret, "HmacSHA256");
        return new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(key));
    }

    public static JwtDecoder decoder(byte[] secret) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(secret, "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> issuerAndTimes = JwtValidators.createDefaultWithIssuer(ISSUER);
        OAuth2TokenValidator<Jwt> audienceAndTenant = jwt ->
                jwt.getAudience().contains(AUDIENCE) && validTenant(jwt.getClaim(TENANT_CLAIM))
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new org.springframework.security.oauth2.core.OAuth2Error("invalid_token"));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuerAndTimes, audienceAndTenant));
        return decoder;
    }

    public static JwtDecoder ownershipLookupDecoder(byte[] secret) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(secret, "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> issuerAndTimes = JwtValidators.createDefaultWithIssuer(ISSUER);
        OAuth2TokenValidator<Jwt> audienceAndScope = jwt ->
                jwt.getAudience().contains(AUDIENCE) && OWNERSHIP_LOOKUP_SCOPE.equals(jwt.getClaim(SCOPE_CLAIM))
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new org.springframework.security.oauth2.core.OAuth2Error("invalid_token"));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuerAndTimes, audienceAndScope));
        return decoder;
    }

    public static String issue(JwtEncoder encoder, UUID tenantId, java.time.Instant now) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER).subject("stream-service").audience(List.of(AUDIENCE))
                .issuedAt(now).expiresAt(now.plusSeconds(60)).claim(TENANT_CLAIM, tenantId.toString()).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    public static String issueOwnershipLookup(JwtEncoder encoder, java.time.Instant now) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER).subject("stream-service").audience(List.of(AUDIENCE))
                .issuedAt(now).expiresAt(now.plusSeconds(60)).claim(SCOPE_CLAIM, OWNERSHIP_LOOKUP_SCOPE).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    public static UUID tenantId(Jwt jwt) {
        Object claim = jwt.getClaim(TENANT_CLAIM);
        if (claim instanceof String value) {
            try { return UUID.fromString(value); } catch (IllegalArgumentException ignored) { }
        }
        throw new IllegalArgumentException("Verified workload token must contain tenant context");
    }

    public static boolean isOwnershipLookup(Jwt jwt) {
        return jwt != null && OWNERSHIP_LOOKUP_SCOPE.equals(jwt.getClaim(SCOPE_CLAIM))
                && ISSUER.equals(jwt.getIssuer().toString()) && jwt.getAudience().contains(AUDIENCE);
    }

    private static boolean validTenant(Object claim) {
        if (!(claim instanceof String value)) return false;
        try { UUID.fromString(value); return true; } catch (IllegalArgumentException ignored) { return false; }
    }
}
