package com.coltrack.events;


import java.time.Instant;
import java.util.UUID;


public record CameraHeartbeatEvent(

        UUID cameraId,

        Instant timestamp

) {
}
