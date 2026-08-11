create table analytics_events
(
    event_id           uuid primary key,
    schema_version     integer      not null default 1,
    event_type         varchar(100) not null,
    camera_id          varchar(100) not null,
    track_id           bigint,
    object_type        varchar(100),
    confidence         numeric(6, 5),
    frame_number       bigint,
    video_time_seconds numeric(14, 3),
    occurred_at        timestamptz  not null,
    received_at        timestamptz  not null default now(),
    recording_id       uuid,
    snapshot_url       text,
    clip_url           text,
    attributes         jsonb        not null default '{}'::jsonb,
    constraint chk_analytics_events_schema_version
        check (schema_version > 0),
    constraint chk_analytics_events_confidence
        check (confidence is null or (confidence >= 0 and confidence <= 1)),
    constraint chk_analytics_events_video_time
        check (video_time_seconds is null or video_time_seconds >= 0)
);

create index idx_analytics_events_camera_time
    on analytics_events (camera_id, occurred_at desc);

create index idx_analytics_events_type_time
    on analytics_events (event_type, occurred_at desc);

create index idx_analytics_events_object_time
    on analytics_events (object_type, occurred_at desc);

create index idx_analytics_events_attributes
    on analytics_events using gin (attributes);
