package com.coltrack.events;

import java.util.UUID;

/**
 * Event для вебсокета
 * @param cameraId
 * @param status
 * @param hlsUrl
 * @param error
 */
public record StreamEvent(

        UUID cameraId,

        String status,

        String hlsUrl,

        String error

) {}
