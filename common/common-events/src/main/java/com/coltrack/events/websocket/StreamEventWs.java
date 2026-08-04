package com.coltrack.events.websocket;

import java.util.UUID;

public record StreamEventWs(

        UUID cameraId,

        String status,

        String hlsUrl,

        String error

) {
}
