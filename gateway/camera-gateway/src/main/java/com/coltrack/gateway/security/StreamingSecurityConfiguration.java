package com.coltrack.gateway.security;

import com.coltrack.security.OidcJwtValidators;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;

@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties(OidcSecurityProperties.class)
@ConditionalOnProperty(name = "app.security.mode", havingValue = "oidc")
public class StreamingSecurityConfiguration {
    @Bean
    ReactiveJwtDecoder streamingJwtDecoder(OidcSecurityProperties properties) {
        properties.validate();
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
                .withJwkSetUri(properties.jwkSetUri()).build();
        decoder.setJwtValidator(OidcJwtValidators.forIssuerAndAudience(
                properties.issuerUri(), properties.audience()));
        return decoder;
    }

    @Bean
    SecurityWebFilterChain streamingSecurityFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchange -> exchange
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .pathMatchers("/actuator/health", "/actuator/info").permitAll()
                        .pathMatchers("/api/playback/**", "/broadcast-play/**").permitAll()
                        .pathMatchers("/media-hls/**", "/hls/**", "/ws", "/ws/**").denyAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
                .build();
    }
}
