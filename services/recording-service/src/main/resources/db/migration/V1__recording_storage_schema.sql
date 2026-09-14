CREATE TABLE IF NOT EXISTS recording_sessions (
    id uuid PRIMARY KEY, camera_id uuid NOT NULL, file_path varchar(255) NOT NULL,
    started_at timestamptz, finished_at timestamptz, duration_seconds bigint,
    size_bytes bigint, segments_count integer, exit_code integer, width integer,
    height integer, fps integer, codec varchar(255), status varchar(32),
    reason varchar(2000), protected_from_deletion boolean NOT NULL DEFAULT false,
    cleanup_status varchar(32) NOT NULL DEFAULT 'AVAILABLE', deleted_at timestamptz,
    deletion_reason varchar(1000)
);
ALTER TABLE recording_sessions ADD COLUMN IF NOT EXISTS protected_from_deletion boolean NOT NULL DEFAULT false;
ALTER TABLE recording_sessions ADD COLUMN IF NOT EXISTS cleanup_status varchar(32) NOT NULL DEFAULT 'AVAILABLE';
ALTER TABLE recording_sessions ADD COLUMN IF NOT EXISTS deleted_at timestamptz;
ALTER TABLE recording_sessions ADD COLUMN IF NOT EXISTS deletion_reason varchar(1000);
CREATE INDEX IF NOT EXISTS idx_recording_sessions_camera_started_at ON recording_sessions(camera_id, started_at);
CREATE INDEX IF NOT EXISTS idx_recording_cleanup_candidates ON recording_sessions(protected_from_deletion, status, cleanup_status, finished_at);

CREATE TABLE IF NOT EXISTS recording_objects (
    id uuid PRIMARY KEY, recording_id uuid NOT NULL, s3_key varchar(1024) NOT NULL,
    file_name varchar(255), size_bytes bigint, sequence_number integer, uploaded_at timestamptz,
    cleanup_status varchar(32) DEFAULT 'AVAILABLE', deleted_at timestamptz,
    deletion_reason varchar(1000), verification_status varchar(32) DEFAULT 'UNKNOWN',
    verified_at timestamptz, actual_size_bytes bigint, verification_error varchar(1000)
);
ALTER TABLE recording_objects ADD COLUMN IF NOT EXISTS cleanup_status varchar(32) DEFAULT 'AVAILABLE';
ALTER TABLE recording_objects ADD COLUMN IF NOT EXISTS deleted_at timestamptz;
ALTER TABLE recording_objects ADD COLUMN IF NOT EXISTS deletion_reason varchar(1000);
ALTER TABLE recording_objects ADD COLUMN IF NOT EXISTS verification_status varchar(32) DEFAULT 'UNKNOWN';
ALTER TABLE recording_objects ADD COLUMN IF NOT EXISTS verified_at timestamptz;
ALTER TABLE recording_objects ADD COLUMN IF NOT EXISTS actual_size_bytes bigint;
ALTER TABLE recording_objects ADD COLUMN IF NOT EXISTS verification_error varchar(1000);
WITH duplicates AS (
    SELECT id, row_number() OVER (PARTITION BY s3_key ORDER BY uploaded_at DESC NULLS LAST, id) AS position
    FROM recording_objects WHERE cleanup_status IS DISTINCT FROM 'DELETED'
)
UPDATE recording_objects object SET cleanup_status='DELETED', deleted_at=now(),
    deletion_reason='Duplicate catalog entry retired by Flyway V1'
FROM duplicates duplicate WHERE object.id=duplicate.id AND duplicate.position>1;
CREATE UNIQUE INDEX IF NOT EXISTS uk_recording_objects_active_s3_key ON recording_objects(s3_key)
    WHERE cleanup_status IS DISTINCT FROM 'DELETED';
CREATE INDEX IF NOT EXISTS idx_recording_objects_recording ON recording_objects(recording_id, sequence_number);

CREATE TABLE IF NOT EXISTS s3_reconciliation_runs (
    id uuid PRIMARY KEY, status varchar(32) NOT NULL, database_objects bigint NOT NULL DEFAULT 0,
    listed_objects bigint NOT NULL DEFAULT 0, verified_objects bigint NOT NULL DEFAULT 0,
    missing_objects bigint NOT NULL DEFAULT 0, size_mismatch_objects bigint NOT NULL DEFAULT 0,
    verification_errors bigint NOT NULL DEFAULT 0, orphan_objects bigint NOT NULL DEFAULT 0,
    delete_incomplete_objects bigint NOT NULL DEFAULT 0, cataloged_bytes bigint NOT NULL DEFAULT 0,
    actual_bytes bigint NOT NULL DEFAULT 0, checked_at timestamptz NOT NULL
);
ALTER TABLE s3_reconciliation_runs ADD COLUMN IF NOT EXISTS database_objects bigint NOT NULL DEFAULT 0;
ALTER TABLE s3_reconciliation_runs ADD COLUMN IF NOT EXISTS listed_objects bigint NOT NULL DEFAULT 0;
ALTER TABLE s3_reconciliation_runs ADD COLUMN IF NOT EXISTS verified_objects bigint NOT NULL DEFAULT 0;
ALTER TABLE s3_reconciliation_runs ADD COLUMN IF NOT EXISTS missing_objects bigint NOT NULL DEFAULT 0;
ALTER TABLE s3_reconciliation_runs ADD COLUMN IF NOT EXISTS size_mismatch_objects bigint NOT NULL DEFAULT 0;
ALTER TABLE s3_reconciliation_runs ADD COLUMN IF NOT EXISTS verification_errors bigint NOT NULL DEFAULT 0;
ALTER TABLE s3_reconciliation_runs ADD COLUMN IF NOT EXISTS orphan_objects bigint NOT NULL DEFAULT 0;
ALTER TABLE s3_reconciliation_runs ADD COLUMN IF NOT EXISTS delete_incomplete_objects bigint NOT NULL DEFAULT 0;
ALTER TABLE s3_reconciliation_runs ADD COLUMN IF NOT EXISTS cataloged_bytes bigint NOT NULL DEFAULT 0;
ALTER TABLE s3_reconciliation_runs ADD COLUMN IF NOT EXISTS actual_bytes bigint NOT NULL DEFAULT 0;
CREATE TABLE IF NOT EXISTS s3_reconciliation_issues (
    id uuid PRIMARY KEY, s3_key varchar(1024) NOT NULL, problem_type varchar(32) NOT NULL,
    actual_size_bytes bigint, details varchar(1000), first_detected_at timestamptz NOT NULL,
    last_checked_at timestamptz NOT NULL, resolved_at timestamptz, resolution varchar(32)
);
ALTER TABLE s3_reconciliation_issues ADD COLUMN IF NOT EXISTS resolution varchar(32);
CREATE UNIQUE INDEX IF NOT EXISTS uk_s3_reconciliation_issue_key ON s3_reconciliation_issues(s3_key);

CREATE TABLE IF NOT EXISTS recording_usage_leases (
    id uuid PRIMARY KEY, recording_id uuid NOT NULL, usage_type varchar(32) NOT NULL,
    owner_id varchar(200) NOT NULL, expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(recording_id, usage_type, owner_id)
);
CREATE INDEX IF NOT EXISTS idx_recording_usage_active ON recording_usage_leases(recording_id, expires_at);

CREATE TABLE IF NOT EXISTS storage_cleanup_runs (
    id uuid PRIMARY KEY, storage_scope varchar(16) NOT NULL, trigger_type varchar(16) NOT NULL,
    status varchar(24) NOT NULL, attempted_count integer NOT NULL DEFAULT 0,
    deleted_count integer NOT NULL DEFAULT 0, failed_count integer NOT NULL DEFAULT 0,
    freed_bytes bigint NOT NULL DEFAULT 0, started_at timestamptz NOT NULL,
    finished_at timestamptz, error varchar(2000)
);
CREATE TABLE IF NOT EXISTS storage_cleanup_run_items (
    id uuid PRIMARY KEY, run_id uuid NOT NULL REFERENCES storage_cleanup_runs(id) ON DELETE CASCADE,
    recording_id uuid, s3_key varchar(1024), status varchar(32) NOT NULL,
    freed_bytes bigint NOT NULL DEFAULT 0, error varchar(2000)
);
CREATE INDEX IF NOT EXISTS idx_storage_cleanup_runs_started ON storage_cleanup_runs(started_at DESC);
