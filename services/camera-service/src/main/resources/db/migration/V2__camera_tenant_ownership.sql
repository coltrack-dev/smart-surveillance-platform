ALTER TABLE cameras ADD COLUMN tenant_id UUID;

CREATE INDEX ix_cameras_tenant_camera_number
    ON cameras (tenant_id, camera_number);

CREATE UNIQUE INDEX uk_cameras_tenant_id_id
    ON cameras (tenant_id, id);
