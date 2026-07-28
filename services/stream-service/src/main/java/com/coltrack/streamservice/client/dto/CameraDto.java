package com.coltrack.streamservice.client.dto;

import java.time.Instant;
import java.util.UUID;

public record CameraDto(

        UUID id,

        String name,

        String location,

        String rtspUrl,

        String status,

        Instant createdAt,

        Instant lastHeartbeat

) {}
