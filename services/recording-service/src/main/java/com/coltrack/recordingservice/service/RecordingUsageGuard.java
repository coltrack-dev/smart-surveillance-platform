package com.coltrack.recordingservice.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.BadSqlGrammarException;

import java.time.Duration;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RecordingUsageGuard {
    private final JdbcTemplate jdbcTemplate;

    public Lease acquire(UUID recordingId, String usageType, Duration ttl) {
        UUID id = UUID.randomUUID();
        String owner = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                insert into recording_usage_leases(id, recording_id, usage_type, owner_id, expires_at)
                values (?, ?, ?, ?, now() + (? * interval '1 second'))
                """, id, recordingId, usageType, owner, ttl.toSeconds());
        return new Lease(id, jdbcTemplate);
    }

    public boolean isInUse(UUID recordingId) {
        jdbcTemplate.update("delete from recording_usage_leases where expires_at <= now()");
        Long leases = jdbcTemplate.queryForObject(
                "select count(*) from recording_usage_leases where recording_id=? and expires_at>now()",
                Long.class, recordingId);
        long analytics = 0;
        try {
            Long count = jdbcTemplate.queryForObject("""
                    select count(*) from analytics_jobs where recording_id=? and job_type='RECORDING'
                      and status in ('REQUESTED','RUNNING','RETRYING','STOP_REQUESTED')
                    """, Long.class, recordingId);
            analytics = count == null ? 0 : count;
        } catch (BadSqlGrammarException ignored) {
            // analytics-service may not have installed its schema yet.
        }
        return (leases != null && leases > 0) || analytics > 0;
    }

    public static final class Lease implements AutoCloseable {
        private final UUID id;
        private final JdbcTemplate jdbcTemplate;
        private Lease(UUID id, JdbcTemplate jdbcTemplate) {
            this.id = id;
            this.jdbcTemplate = jdbcTemplate;
        }
        @Override public void close() {
            jdbcTemplate.update("delete from recording_usage_leases where id=?", id);
        }
    }
}
