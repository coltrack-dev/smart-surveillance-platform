ALTER TABLE broadcasts ADD COLUMN tenant_id UUID;
ALTER TABLE stream_consumer_leases ADD COLUMN tenant_id UUID;

CREATE INDEX ix_broadcasts_tenant_enabled_updated
    ON broadcasts (tenant_id, enabled, updated_at DESC);

CREATE INDEX ix_broadcasts_tenant_camera
    ON broadcasts (tenant_id, camera_id);

CREATE INDEX ix_stream_consumer_leases_tenant_camera
    ON stream_consumer_leases (tenant_id, camera_id);
