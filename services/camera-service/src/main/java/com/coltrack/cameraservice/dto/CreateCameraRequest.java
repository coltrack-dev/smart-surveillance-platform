package com.coltrack.cameraservice.dto;

import java.util.UUID;

public record CreateCameraRequest(

        String name,

        UUID lbsLocationId,

        UUID categoryId,

        String rtspUrl,

        boolean autoStart

) {
}
