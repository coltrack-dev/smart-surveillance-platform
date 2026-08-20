alter table analytics_jobs
    add column recording_id uuid;

create index idx_analytics_jobs_recording_created
    on analytics_jobs (recording_id, created_at desc)
    where recording_id is not null;
