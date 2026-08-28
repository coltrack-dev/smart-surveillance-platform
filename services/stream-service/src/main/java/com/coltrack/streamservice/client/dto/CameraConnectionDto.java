package com.coltrack.streamservice.client.dto;

import com.coltrack.streamservice.model.VideoProcessingMode;

import java.util.UUID;

public record CameraConnectionDto(
        UUID id,
        String rtspUrl,
        VideoProcessingMode videoProcessingMode
) {
}
