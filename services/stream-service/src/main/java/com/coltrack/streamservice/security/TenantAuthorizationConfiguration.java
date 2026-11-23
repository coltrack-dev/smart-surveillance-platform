package com.coltrack.streamservice.security;

import com.coltrack.identity.security.JdbcTenantMembershipResolver;
import com.coltrack.identity.security.TenantAuthorization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class TenantAuthorizationConfiguration {
    @Bean
    JdbcTenantMembershipResolver streamMembershipResolver(JdbcTemplate jdbc) {
        return new JdbcTenantMembershipResolver(jdbc);
    }

    @Bean
    TenantAuthorization streamTenantAuthorization(JdbcTenantMembershipResolver resolver) {
        return new TenantAuthorization(resolver, resolver);
    }
}
