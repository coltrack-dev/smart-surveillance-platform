package com.coltrack.events.websocket;

import java.time.Instant;
import java.util.UUID;


public record CameraStatusEventWs(

        UUID cameraId,

        String status,

        String reason,

        Instant changedAt

) {}