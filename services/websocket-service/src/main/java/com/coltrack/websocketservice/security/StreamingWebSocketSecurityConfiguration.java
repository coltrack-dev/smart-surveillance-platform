package com.coltrack.websocketservice.security;

import com.coltrack.identity.security.JdbcTenantMembershipResolver;
import com.coltrack.identity.security.TenantAuthorization;
import com.coltrack.security.OidcJwtValidators;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration
@ConditionalOnProperty(name = "app.security.mode", havingValue = "oidc")
public class StreamingWebSocketSecurityConfiguration {
    @Bean(destroyMethod = "close")
    HikariDataSource webSocketTenantDataSource(@Value("${spring.datasource.url:}") String url,
                                                @Value("${spring.datasource.username:}") String username,
                                                @Value("${spring.datasource.password:}") String password) {
        if (blank(url) || blank(username) || blank(password)) {
            throw new IllegalStateException("Streaming WebSocket identity and Camera database settings are required");
        }
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setMaximumPoolSize(4);
        dataSource.setMinimumIdle(1);
        dataSource.setPoolName("tenant-websocket-read-pool");
        return dataSource;
    }

    @Bean
    JwtDecoder webSocketJwtDecoder(@Value("${app.security.oidc.issuer-uri:}") String issuer,
                                   @Value("${app.security.oidc.jwk-set-uri:}") String jwks,
                                   @Value("${app.security.oidc.audience:}") String audience) {
        if (blank(issuer) || blank(jwks) || blank(audience)) {
            throw new IllegalStateException("Streaming WebSocket OIDC issuer, JWKS and audience are required");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
        decoder.setJwtValidator(OidcJwtValidators.forIssuerAndAudience(issuer, audience));
        return decoder;
    }

    @Bean
    JdbcTenantMembershipResolver webSocketMembershipResolver(JdbcTemplate jdbc) {
        return new JdbcTenantMembershipResolver(jdbc);
    }

    @Bean
    TenantAuthorization webSocketTenantAuthorization(JdbcTenantMembershipResolver resolver) {
        return new TenantAuthorization(resolver, resolver);
    }

    @Bean
    CameraTenantLookup cameraTenantLookup(JdbcTemplate jdbc) {
        return new CameraTenantLookup(jdbc);
    }

    @Bean
    TenantStompSecurityInterceptor tenantStompSecurityInterceptor(JwtDecoder webSocketJwtDecoder,
                                                                  TenantAuthorization webSocketTenantAuthorization) {
        return new TenantStompSecurityInterceptor(webSocketJwtDecoder, webSocketTenantAuthorization);
    }

    @Bean
    TenantSocketOutboundInterceptor tenantSocketOutboundInterceptor(TenantStompSecurityInterceptor sessions) {
        return new TenantSocketOutboundInterceptor(sessions);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
