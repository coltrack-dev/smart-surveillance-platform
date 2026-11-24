package com.coltrack.identityservice.repository;

import com.coltrack.identityservice.model.QuotaDtos.OrganizationQuotas;
import com.coltrack.identityservice.model.QuotaDtos.Limits;
import com.coltrack.identityservice.model.QuotaDtos.Overrides;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class QuotaPlanRepository {
    private final JdbcTemplate jdbc;

    public QuotaPlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void assignDefault(UUID organizationId, String planCode) {
        jdbc.update("""
                INSERT INTO identity.tenant_quota_assignments (tenant_id, plan_code)
                VALUES (?, ?)
                """, organizationId, planCode);
    }

    public Optional<OrganizationQuotas> findEffective(UUID organizationId, String defaultPlanCode) {
        return jdbc.query("""
                SELECT t.id,
                       p.plan_code,
                       p.display_name,
                       COALESCE(o.max_cameras, p.max_cameras) AS effective_max_cameras,
                       COALESCE(o.max_active_broadcasts, p.max_active_broadcasts) AS effective_max_active_broadcasts,
                       COALESCE(o.max_concurrent_viewers, p.max_concurrent_viewers) AS effective_max_concurrent_viewers,
                       o.max_cameras AS override_max_cameras,
                       o.max_active_broadcasts AS override_max_active_broadcasts,
                       o.max_concurrent_viewers AS override_max_concurrent_viewers
                FROM identity.tenants t
                LEFT JOIN identity.tenant_quota_assignments a ON a.tenant_id = t.id
                JOIN identity.quota_plans p
                  ON p.plan_code = COALESCE(a.plan_code, ?)
                 AND p.enabled = TRUE
                LEFT JOIN identity.tenant_quota_overrides o ON o.tenant_id = t.id
                WHERE t.id = ? AND t.status = 'ACTIVE'
                """, (rs, row) -> new OrganizationQuotas(
                rs.getObject("id", UUID.class), rs.getString("plan_code"), rs.getString("display_name"),
                new Limits(rs.getInt("effective_max_cameras"), rs.getInt("effective_max_active_broadcasts"),
                        rs.getInt("effective_max_concurrent_viewers")),
                new Overrides(nullableInt(rs, "override_max_cameras"),
                        nullableInt(rs, "override_max_active_broadcasts"),
                        nullableInt(rs, "override_max_concurrent_viewers"))), defaultPlanCode, organizationId)
                .stream().findFirst();
    }

    private static Integer nullableInt(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
