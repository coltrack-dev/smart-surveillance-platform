alter table analytics_jobs
    add column version bigint not null default 0;

create unique index uq_analytics_jobs_active_realtime_camera
    on analytics_jobs (camera_id)
    where job_type = 'REALTIME'
      and status in ('REQUESTED', 'RUNNING', 'RETRYING', 'STOP_REQUESTED');

create unique index uq_analytics_jobs_active_recording
    on analytics_jobs (recording_id)
    where job_type = 'RECORDING'
      and recording_id is not null
      and status in ('REQUESTED', 'RUNNING', 'RETRYING', 'STOP_REQUESTED');
