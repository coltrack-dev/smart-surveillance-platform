package com.coltrack.cameraservice.config;

import com.coltrack.identity.security.JdbcTenantMembershipResolver;
import com.coltrack.identity.security.TenantAuthorization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class TenantAuthorizationConfiguration {
    @Bean
    JdbcTenantMembershipResolver cameraMembershipResolver(JdbcTemplate jdbc) {
        return new JdbcTenantMembershipResolver(jdbc);
    }

    @Bean
    TenantAuthorization cameraTenantAuthorization(JdbcTenantMembershipResolver resolver) {
        return new TenantAuthorization(resolver, resolver);
    }
}
