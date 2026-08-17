create table analytics_jobs
(
    job_id          uuid primary key,
    camera_id       uuid                     not null,
    job_type        varchar(32)              not null,
    status          varchar(32)              not null,
    worker_id       varchar(200),
    source_url      text,
    source_transport varchar(16),
    profile         jsonb                    not null default '{}'::jsonb,
    details         jsonb                    not null default '{}'::jsonb,
    created_at      timestamp with time zone not null,
    updated_at      timestamp with time zone not null,
    started_at      timestamp with time zone,
    finished_at     timestamp with time zone
);

create index idx_analytics_jobs_camera_created
    on analytics_jobs (camera_id, created_at desc);

create index idx_analytics_jobs_status
    on analytics_jobs (status);

create table analytics_workers
(
    worker_id         varchar(200) primary key,
    status            varchar(32)              not null,
    active_jobs       integer                  not null,
    max_jobs          integer                  not null,
    host              varchar(255),
    platform          text,
    cuda_available    boolean                  not null,
    cuda_device_count integer                  not null,
    gpu_name          varchar(255),
    last_seen_at      timestamp with time zone not null
);

create index idx_analytics_workers_last_seen
    on analytics_workers (last_seen_at desc);
