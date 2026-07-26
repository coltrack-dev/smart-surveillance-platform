package com.coltrack.events;


import java.time.Instant;
import java.util.UUID;


public record CameraStatusChangedEvent(

        UUID cameraId,

        String status,

        Instant changedAt

) {}
