CREATE TABLE camera_deletion_tombstones (
    camera_id UUID PRIMARY KEY,
    tenant_id UUID,
    deleted_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_camera_deletion_tombstones_tenant_deleted
    ON camera_deletion_tombstones (tenant_id, deleted_at DESC);
