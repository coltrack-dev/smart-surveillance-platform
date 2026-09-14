package com.coltrack.recordingservice.service;

import com.coltrack.recordingservice.dto.CleanupRunHistoryResponse;
import com.coltrack.recordingservice.dto.CleanupRunHistoryItemResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class CleanupHistoryService {
    private final JdbcTemplate jdbcTemplate;

    public <T> T track(
            String scope,
            String trigger,
            Supplier<T> operation,
            Function<T, Metrics> metrics,
            Function<T, List<Item>> items
    ) {
        UUID id = UUID.randomUUID();
        Instant started = Instant.now();
        jdbcTemplate.update("""
                insert into storage_cleanup_runs(id, storage_scope, trigger_type, status, started_at)
                values (?, ?, ?, 'RUNNING', ?)
                """, id, scope, trigger, Timestamp.from(started));
        try {
            T result = operation.get();
            Metrics value = metrics.apply(result);
            for (Item item : items.apply(result)) {
                jdbcTemplate.update("""
                        insert into storage_cleanup_run_items(
                            id, run_id, recording_id, s3_key, status, freed_bytes, error
                        ) values (?, ?, ?, ?, ?, ?, ?)
                        """, UUID.randomUUID(), id, item.recordingId(), item.s3Key(), item.status(),
                        item.freedBytes(), item.error());
            }
            jdbcTemplate.update("""
                    update storage_cleanup_runs set status='COMPLETED', attempted_count=?, deleted_count=?,
                    failed_count=?, freed_bytes=?, finished_at=? where id=?
                    """, value.attempted(), value.deleted(), value.failed(), value.freedBytes(),
                    Timestamp.from(Instant.now()), id);
            return result;
        } catch (RuntimeException exception) {
            jdbcTemplate.update("update storage_cleanup_runs set status='FAILED', error=?, finished_at=? where id=?",
                    safeMessage(exception), Timestamp.from(Instant.now()), id);
            throw exception;
        }
    }

    public List<CleanupRunHistoryResponse> latest(int limit) {
        return jdbcTemplate.query("""
                select id, storage_scope, trigger_type, status, attempted_count, deleted_count,
                       failed_count, freed_bytes, started_at, finished_at, error
                from storage_cleanup_runs order by started_at desc limit ?
                """, (rs, row) -> new CleanupRunHistoryResponse(
                rs.getObject("id", UUID.class), rs.getString("storage_scope"),
                rs.getString("trigger_type"), rs.getString("status"),
                rs.getInt("attempted_count"), rs.getInt("deleted_count"),
                rs.getInt("failed_count"), rs.getLong("freed_bytes"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant(),
                rs.getString("error")), Math.min(Math.max(limit, 1), 100));
    }

    public List<CleanupRunHistoryItemResponse> items(UUID runId) {
        return jdbcTemplate.query("""
                select id, recording_id, s3_key, status, freed_bytes, error
                from storage_cleanup_run_items where run_id=? order by id
                """, (rs, row) -> new CleanupRunHistoryItemResponse(
                rs.getObject("id", UUID.class), rs.getObject("recording_id", UUID.class),
                rs.getString("s3_key"), rs.getString("status"), rs.getLong("freed_bytes"),
                rs.getString("error")), runId);
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null ? exception.getClass().getSimpleName() : message.substring(0, Math.min(2000, message.length()));
    }

    public record Metrics(int attempted, int deleted, int failed, long freedBytes) {
    }

    public record Item(UUID recordingId, String s3Key, String status, long freedBytes, String error) {
    }
}
