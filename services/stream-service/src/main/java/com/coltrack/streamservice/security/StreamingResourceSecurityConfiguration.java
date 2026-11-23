package com.coltrack.streamservice.security;

import com.coltrack.security.OidcJwtValidators;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableConfigurationProperties(OidcSecurityProperties.class)
@ConditionalOnProperty(name = "app.security.mode", havingValue = "oidc")
public class StreamingResourceSecurityConfiguration {
    @Bean
    JwtDecoder streamServiceJwtDecoder(OidcSecurityProperties properties) {
        properties.validate();
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
        decoder.setJwtValidator(OidcJwtValidators.forIssuerAndAudience(
                properties.issuerUri(), properties.audience()));
        return decoder;
    }

    @Bean
    SecurityFilterChain streamServiceSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/playback/**", "/broadcast-play/**").permitAll()
                        .requestMatchers("/hls/**", "/ws", "/ws/**").denyAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
                .build();
    }
}
