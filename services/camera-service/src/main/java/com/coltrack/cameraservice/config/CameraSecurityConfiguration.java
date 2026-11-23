package com.coltrack.cameraservice.config;

import com.coltrack.identity.security.TenantWorkloadJwt;
import com.coltrack.security.OidcJwtValidators;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import javax.crypto.spec.SecretKeySpec;

@Configuration
@ConditionalOnProperty(name = "app.security.mode", havingValue = "oidc")
public class CameraSecurityConfiguration {
    @Bean("cameraOidcJwtDecoder")
    JwtDecoder cameraOidcJwtDecoder(@Value("${app.security.oidc.issuer-uri:}") String issuer,
                                    @Value("${app.security.oidc.jwk-set-uri:}") String jwks,
                                    @Value("${app.security.oidc.audience:}") String audience) {
        if (blank(issuer) || blank(jwks) || blank(audience)) {
            throw new IllegalStateException("OIDC issuer, JWKS URI and audience are required in Streaming mode");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
        decoder.setJwtValidator(OidcJwtValidators.forIssuerAndAudience(issuer, audience));
        return decoder;
    }

    @Bean("cameraWorkloadJwtDecoder")
    JwtDecoder cameraWorkloadJwtDecoder(@Value("${app.security.internal.workload-secret:}") String secret) {
        return TenantWorkloadJwt.decoder(TenantWorkloadJwt.decodeSecret(secret));
    }

    @Bean("cameraOwnershipJwtDecoder")
    JwtDecoder cameraOwnershipJwtDecoder(@Value("${app.security.internal.workload-secret:}") String secret) {
        return TenantWorkloadJwt.ownershipLookupDecoder(TenantWorkloadJwt.decodeSecret(secret));
    }

    @Bean
    @Order(0)
    SecurityFilterChain internalCameraOwnershipApi(HttpSecurity http,
            @Qualifier("cameraOwnershipJwtDecoder") JwtDecoder decoder) throws Exception {
        return http.securityMatcher("/internal/cameras/*/ownership")
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(decoder)))
                .build();
    }

    @Bean
    @Order(1)
    SecurityFilterChain internalCameraApi(HttpSecurity http,
            @Qualifier("cameraWorkloadJwtDecoder") JwtDecoder decoder) throws Exception {
        return http.securityMatcher("/internal/**")
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(decoder)))
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain streamingCameraApi(HttpSecurity http,
            @Qualifier("cameraOidcJwtDecoder") JwtDecoder decoder) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(decoder)))
                .build();
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
