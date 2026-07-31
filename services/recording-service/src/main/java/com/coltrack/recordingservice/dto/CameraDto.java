package com.coltrack.recordingservice.dto;

import java.util.UUID;

public record CameraDto(
        UUID id,
        String rtspUrl
) {
}
