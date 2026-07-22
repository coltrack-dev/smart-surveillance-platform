package com.coltrack.events;

import java.time.Instant;
import java.util.UUID;


public record CameraRegisteredEvent(

        UUID cameraId,

        String name,

        String location,

        Instant createdAt

) {}
