package com.coltrack.identity.quota;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.sql.ResultSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TenantQuotaGuardTest {
    @Test
    void rejectsAtLimitAfterAcquiringOrganizationLock() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID tenantId = UUID.randomUUID();
        when(jdbc.query(contains("pg_advisory_xact_lock"), any(ResultSetExtractor.class), eq(tenantId.toString()))).thenAnswer(invocation -> {
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true);
            return invocation.<ResultSetExtractor<Object>>getArgument(1).extractData(rs);
        });
        when(jdbc.queryForObject(contains("identity.tenants"), eq(Boolean.class), eq(tenantId))).thenReturn(true);
        when(jdbc.query(contains("quota_plans"), any(ResultSetExtractor.class), eq("FREE"), eq(tenantId)))
                .thenAnswer(invocation -> {
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.next()).thenReturn(true);
                    when(rs.getInt(1)).thenReturn(2);
                    when(rs.getInt(2)).thenReturn(1);
                    return invocation.<ResultSetExtractor<Object>>getArgument(1).extractData(rs);
                });
        when(jdbc.queryForObject(contains("count(*) FROM cameras"), eq(Long.class), eq(tenantId))).thenReturn(2L);

        TenantQuotaGuard guard = new TenantQuotaGuard(jdbc, "FREE");

        TenantQuotaGuard.QuotaExceededException error = assertThrows(TenantQuotaGuard.QuotaExceededException.class,
                () -> guard.reserve(tenantId, TenantQuotaGuard.Resource.CAMERA));

        assertEquals("CAMERA_QUOTA_EXCEEDED", error.code());
        assertEquals(2, error.used());
        assertEquals(2, error.limit());
        verify(jdbc).query(contains("pg_advisory_xact_lock"), any(ResultSetExtractor.class), eq(tenantId.toString()));
    }

    @Test
    void databaseLookupFailureFailsClosed() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID tenantId = UUID.randomUUID();
        when(jdbc.query(contains("pg_advisory_xact_lock"), any(ResultSetExtractor.class), eq(tenantId.toString())))
                .thenThrow(new DataAccessResourceFailureException("synthetic identity schema outage"));

        TenantQuotaGuard guard = new TenantQuotaGuard(jdbc, "FREE");

        assertThrows(TenantQuotaGuard.QuotaAuthorityUnavailableException.class,
                () -> guard.reserve(tenantId, TenantQuotaGuard.Resource.CAMERA));
        verify(jdbc, never()).queryForObject(contains("count(*) FROM cameras"), eq(Long.class), any());
    }
}
