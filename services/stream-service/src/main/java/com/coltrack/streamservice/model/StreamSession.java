package com.coltrack.streamservice.model;


import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;


@Data
@Builder
public class StreamSession {


    private UUID cameraId;


    private String rtspUrl;


    private StreamStatus status;


    private Instant startedAt;


    private Instant lastFrameTime;

}
