ALTER TABLE broadcasts
    ADD COLUMN public_id UUID,
    ADD COLUMN visibility VARCHAR(16) NOT NULL DEFAULT 'PRIVATE';

UPDATE broadcasts SET public_id = gen_random_uuid() WHERE public_id IS NULL;

ALTER TABLE broadcasts
    ALTER COLUMN public_id SET NOT NULL,
    ADD CONSTRAINT uk_broadcast_public_id UNIQUE (public_id),
    ADD CONSTRAINT ck_broadcast_visibility CHECK (visibility IN ('PUBLIC', 'PRIVATE'));

CREATE TABLE stream_consumer_leases (
    camera_id UUID NOT NULL,
    consumer_id VARCHAR(160) NOT NULL,
    consumer_type VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (camera_id, consumer_id)
);

CREATE INDEX idx_stream_consumer_leases_camera ON stream_consumer_leases (camera_id);

INSERT INTO stream_consumer_leases (camera_id, consumer_id, consumer_type, created_at)
SELECT camera_id, 'broadcast:' || id::text, 'BROADCAST', updated_at
FROM broadcasts
WHERE enabled = TRUE
ON CONFLICT (camera_id, consumer_id) DO NOTHING;
