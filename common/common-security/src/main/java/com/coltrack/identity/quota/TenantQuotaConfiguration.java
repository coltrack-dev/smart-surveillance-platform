package com.coltrack.identity.quota;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class TenantQuotaConfiguration {
    @Bean
    TenantQuotaGuard tenantQuotaGuard(JdbcTemplate jdbc,
            @Value("${identity.quotas.default-plan:FREE}") String defaultPlan) {
        return new TenantQuotaGuard(jdbc, defaultPlan);
    }
}
