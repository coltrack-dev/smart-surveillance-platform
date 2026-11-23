CREATE TABLE broadcasts (
    id UUID PRIMARY KEY,
    camera_id UUID NOT NULL UNIQUE,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    owns_stream BOOLEAN NOT NULL DEFAULT FALSE,
    playback_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_broadcasts_enabled ON broadcasts (enabled);
