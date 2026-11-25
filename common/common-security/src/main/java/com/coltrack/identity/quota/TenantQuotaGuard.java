package com.coltrack.identity.quota;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.util.UUID;

/** Shared-database quota authority. Call admission methods inside the resource owner's transaction. */
public final class TenantQuotaGuard {
    private final JdbcTemplate jdbc;
    private final String defaultPlan;

    public TenantQuotaGuard(JdbcTemplate jdbc, String defaultPlan) {
        this.jdbc = jdbc;
        this.defaultPlan = defaultPlan;
    }

    public Usage usage(UUID tenantId, Resource resource) {
        try {
            Limits limits = limits(tenantId);
            long used = count(tenantId, resource);
            int limit = resource == Resource.CAMERA ? limits.cameras : limits.broadcasts;
            return new Usage(tenantId, resource, used, limit, Math.max(0, limit - used));
        } catch (DataAccessException | IllegalStateException e) {
            throw new QuotaAuthorityUnavailableException(e);
        }
    }

    public void reserve(UUID tenantId, Resource resource) {
        try {
            acquireTenantLock(tenantId);
            requireActiveTenant(tenantId);
            Limits limits = limits(tenantId);
            long used = count(tenantId, resource);
            int limit = resource == Resource.CAMERA ? limits.cameras : limits.broadcasts;
            if (used >= limit) throw new QuotaExceededException(resource, used, limit);
        } catch (QuotaExceededException e) {
            throw e;
        } catch (DataAccessException | IllegalStateException e) {
            throw new QuotaAuthorityUnavailableException(e);
        }
    }

    public void lock(UUID tenantId) {
        try {
            acquireTenantLock(tenantId);
            requireActiveTenant(tenantId);
        } catch (DataAccessException | IllegalStateException e) {
            throw new QuotaAuthorityUnavailableException(e);
        }
    }

    private void acquireTenantLock(UUID tenantId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                (ResultSetExtractor<Void>) rs -> { if (!rs.next()) throw new IllegalStateException("Quota lock unavailable"); return null; }, tenantId.toString());
    }

    private void requireActiveTenant(UUID tenantId) {
        Boolean active = jdbc.queryForObject("SELECT identity.is_active_tenant(?)",
                Boolean.class, tenantId);
        if (!Boolean.TRUE.equals(active)) throw new IllegalStateException("Organization unavailable");
    }

    public void requireCapacity(UUID tenantId, Resource resource) {
        try {
            Limits limits = limits(tenantId);
            long used = count(tenantId, resource);
            int limit = resource == Resource.CAMERA ? limits.cameras : limits.broadcasts;
            if (used >= limit) throw new QuotaExceededException(resource, used, limit);
        } catch (QuotaExceededException e) {
            throw e;
        } catch (DataAccessException | IllegalStateException e) {
            throw new QuotaAuthorityUnavailableException(e);
        }
    }

    private Limits limits(UUID tenantId) {
        return jdbc.query("""
                SELECT max_cameras, max_active_broadcasts
                FROM identity.effective_tenant_quota(?, ?)
                """, rs -> {
            if (!rs.next()) throw new IllegalStateException("Effective quota unavailable");
            return new Limits(rs.getInt(1), rs.getInt(2));
        }, tenantId, defaultPlan);
    }

    private long count(UUID tenantId, Resource resource) {
        String sql = switch (resource) {
            case CAMERA -> "SELECT count(*) FROM cameras WHERE tenant_id = ?";
            case BROADCAST -> "SELECT count(*) FROM broadcasts WHERE tenant_id = ? AND enabled = TRUE";
        };
        Long count = jdbc.queryForObject(sql, Long.class, tenantId);
        return count == null ? 0 : count;
    }

    private record Limits(int cameras, int broadcasts) {}
    public enum Resource { CAMERA, BROADCAST }
    public record Usage(UUID tenantId, Resource resource, long used, int limit, long remaining) {}

    public static final class QuotaExceededException extends RuntimeException {
        private final Resource resource; private final long used; private final int limit;
        public QuotaExceededException(Resource resource, long used, int limit) {
            super("Resource quota exceeded"); this.resource = resource; this.used = used; this.limit = limit;
        }
        public Resource resource() { return resource; }
        public long used() { return used; }
        public int limit() { return limit; }
        public String code() { return resource == Resource.CAMERA ? "CAMERA_QUOTA_EXCEEDED" : "ACTIVE_BROADCAST_QUOTA_EXCEEDED"; }
    }

    public static final class QuotaAuthorityUnavailableException extends RuntimeException {
        public QuotaAuthorityUnavailableException(Throwable cause) { super("Quota authority unavailable", cause); }
    }
}
