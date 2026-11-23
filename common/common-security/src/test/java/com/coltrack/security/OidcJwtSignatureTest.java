package com.coltrack.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OidcJwtSignatureTest {
    private static final String ISSUER = "https://issuer.example/realms/streaming";

    @Test
    void validatesRsaSignatureAndRejectsModifiedSignature() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        RSAKey signingKey = new RSAKey.Builder((java.security.interfaces.RSAPublicKey) pair.getPublic())
                .privateKey((java.security.interfaces.RSAPrivateKey) pair.getPrivate())
                .keyID("test-key")
                .build();
        JwtEncoder encoder = new NimbusJwtEncoder(
                new ImmutableJWKSet<SecurityContext>(new JWKSet(signingKey)));
        JwtDecoder decoder = NimbusJwtDecoder.withPublicKey(
                (java.security.interfaces.RSAPublicKey) pair.getPublic()).build();
        ((NimbusJwtDecoder) decoder).setJwtValidator(
                OidcJwtValidators.forIssuerAndAudience(ISSUER, "streaming-api"));
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject("subject-1")
                .audience(List.of("streaming-api"))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(120))
                .notBefore(now.minusSeconds(1))
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).keyId("test-key").build(), claims))
                .getTokenValue();

        assertEquals("subject-1", decoder.decode(token).getSubject());
        String[] parts = token.split("\\.");
        String altered = parts[0] + "." + parts[1] + "." + (parts[2].endsWith("a") ? "b" : "a");
        assertThrows(Exception.class, () -> decoder.decode(altered));
    }
}
