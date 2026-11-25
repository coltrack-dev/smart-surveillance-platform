package com.coltrack.streamservice.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ViewerSessionRepository {
    private final JdbcTemplate jdbc;

    public ViewerSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ViewerSession> findByCredentialHash(byte[] credentialHash) {
        return jdbc.query("""
                SELECT session_id, tenant_id, broadcast_id, user_id, expires_at, revoked_at
                FROM viewer_sessions WHERE credential_hash = ?
                """, rs -> rs.next() ? Optional.of(new ViewerSession(
                rs.getObject("session_id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("broadcast_id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant())) : Optional.empty(),
                credentialHash);
    }

    public void create(UUID sessionId, UUID tenantId, UUID broadcastId, UUID userId,
                       byte[] credentialHash, Instant now, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO viewer_sessions
                    (session_id, tenant_id, broadcast_id, user_id, credential_hash,
                     created_at, last_seen_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, sessionId, tenantId, broadcastId, userId, credentialHash,
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(expiresAt));
    }

    public void renew(UUID previousSessionId, UUID nextSessionId, Instant now, Instant expiresAt) {
        jdbc.update("""
                UPDATE viewer_sessions
                SET session_id = ?, last_seen_at = ?, expires_at = ?, revoked_at = NULL
                WHERE session_id = ?
                """, nextSessionId, Timestamp.from(now), Timestamp.from(expiresAt), previousSessionId);
    }

    public void renew(UUID sessionId, Instant now, Instant expiresAt) {
        jdbc.update("""
                UPDATE viewer_sessions SET last_seen_at = ?, expires_at = ?
                WHERE session_id = ? AND revoked_at IS NULL
                """, Timestamp.from(now), Timestamp.from(expiresAt), sessionId);
    }

    public boolean isActive(UUID sessionId, UUID tenantId, UUID broadcastId, UUID userId, Instant now) {
        List<Boolean> rows = jdbc.query("""
                SELECT EXISTS (
                    SELECT 1 FROM viewer_sessions
                    WHERE session_id = ? AND tenant_id = ? AND broadcast_id = ?
                      AND user_id IS NOT DISTINCT FROM ?
                      AND revoked_at IS NULL AND expires_at > ?
                )
                """, (rs, row) -> rs.getBoolean(1), sessionId, tenantId, broadcastId, userId, Timestamp.from(now));
        return !rows.isEmpty() && rows.getFirst();
    }

    public void revoke(UUID sessionId, Instant now) {
        jdbc.update("UPDATE viewer_sessions SET revoked_at = ? WHERE session_id = ? AND revoked_at IS NULL",
                Timestamp.from(now), sessionId);
    }

    public int deleteExpiredBatch(Instant now, int batchSize) {
        int expired = jdbc.update("""
                WITH expired AS (
                    SELECT session_id FROM viewer_sessions
                    WHERE expires_at <= ? AND revoked_at IS NULL
                    ORDER BY expires_at, session_id
                    LIMIT ? FOR UPDATE SKIP LOCKED
                )
                DELETE FROM viewer_sessions s USING expired e WHERE s.session_id = e.session_id
                """, Timestamp.from(now), batchSize);
        int revoked = jdbc.update("""
                WITH revoked AS (
                    SELECT session_id FROM viewer_sessions
                    WHERE revoked_at IS NOT NULL
                    ORDER BY revoked_at, session_id
                    LIMIT ? FOR UPDATE SKIP LOCKED
                )
                DELETE FROM viewer_sessions s USING revoked r WHERE s.session_id = r.session_id
                """, batchSize);
        return expired + revoked;
    }

    public record ViewerSession(UUID sessionId, UUID tenantId, UUID broadcastId, UUID userId,
                                Instant expiresAt, Instant revokedAt) {
        public boolean expiredAt(Instant now) { return !expiresAt.isAfter(now); }
        public boolean revoked() { return revokedAt != null; }
    }
}
