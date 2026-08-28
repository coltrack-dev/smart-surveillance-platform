package com.coltrack.cameraservice.dto;

import com.coltrack.cameraservice.entity.VideoProcessingMode;

import java.util.UUID;

public record CameraConnectionResponse(
        UUID id,
        String rtspUrl,
        VideoProcessingMode videoProcessingMode
) {
}
