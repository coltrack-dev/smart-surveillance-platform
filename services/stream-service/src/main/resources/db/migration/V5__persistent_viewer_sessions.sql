CREATE TABLE viewer_sessions (
    session_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    broadcast_id UUID NOT NULL REFERENCES broadcasts(id) ON DELETE CASCADE,
    user_id UUID,
    credential_hash BYTEA NOT NULL UNIQUE CHECK (octet_length(credential_hash) = 32),
    created_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT ck_viewer_session_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_viewer_session_last_seen CHECK (last_seen_at >= created_at)
);

CREATE INDEX ix_viewer_sessions_active_tenant_expiry
    ON viewer_sessions (tenant_id, expires_at)
    WHERE revoked_at IS NULL;

CREATE INDEX ix_viewer_sessions_active_broadcast_expiry
    ON viewer_sessions (broadcast_id, expires_at)
    WHERE revoked_at IS NULL;

CREATE INDEX ix_viewer_sessions_cleanup
    ON viewer_sessions (expires_at, session_id)
    WHERE revoked_at IS NULL;

CREATE INDEX ix_viewer_sessions_revoked_cleanup
    ON viewer_sessions (revoked_at, session_id)
    WHERE revoked_at IS NOT NULL;
