ALTER TABLE cameras
    ADD COLUMN IF NOT EXISTS rtsp_username VARCHAR(255);

ALTER TABLE cameras
    ADD COLUMN IF NOT EXISTS rtsp_password_encrypted TEXT;

ALTER TABLE cameras
    ADD COLUMN IF NOT EXISTS rtsp_url_format VARCHAR(32);

ALTER TABLE cameras
    ADD COLUMN IF NOT EXISTS video_processing_mode VARCHAR(32);

UPDATE cameras
SET rtsp_url_format = 'STANDARD'
WHERE rtsp_url_format IS NULL;

UPDATE cameras
SET video_processing_mode = 'AUTO'
WHERE video_processing_mode IS NULL;

ALTER TABLE cameras
    ALTER COLUMN rtsp_url_format SET DEFAULT 'STANDARD',
ALTER COLUMN rtsp_url_format SET NOT NULL,
    ALTER COLUMN video_processing_mode SET DEFAULT 'AUTO',
    ALTER COLUMN video_processing_mode SET NOT NULL;
