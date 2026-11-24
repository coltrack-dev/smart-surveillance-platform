CREATE TABLE camera_create_idempotency (
    tenant_id UUID NOT NULL,
    idempotency_key_hash CHAR(64) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    camera_id UUID REFERENCES cameras(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, idempotency_key_hash)
);

CREATE INDEX ix_camera_create_idempotency_created_at
    ON camera_create_idempotency (created_at);
