package com.coltrack.cameraservice.dto;

import java.time.Instant;
import java.util.UUID;

public record CameraResponse(

        UUID id,

        String name,

        String location,

        Instant createdAt

){}
