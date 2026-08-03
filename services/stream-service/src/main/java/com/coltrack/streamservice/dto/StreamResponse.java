package com.coltrack.streamservice.dto;

import com.coltrack.streamservice.model.StreamStatus;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class StreamResponse {

    private UUID cameraId;

    private StreamStatus status;

    private String hlsUrl;

    private Instant startedAt;

    private int reconnectCount;

    private String lastError;
}
