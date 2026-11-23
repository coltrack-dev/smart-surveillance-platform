package com.coltrack.cameraservice.dto;

import com.coltrack.cameraservice.entity.CameraEntity;
import java.time.Instant;
import java.util.UUID;

/** Safe tenant-facing representation; RTSP addresses and credentials stay server-side. */
public record CameraResourceResponse(UUID id, Integer cameraNumber, String name, String status,
                                     Instant createdAt, Instant lastHeartbeat, boolean autoStart,
                                     String location) {
    public static CameraResourceResponse from(CameraEntity camera) {
        return new CameraResourceResponse(camera.getId(), camera.getCameraNumber(), camera.getName(),
                camera.getStatus().name(), camera.getCreatedAt(), camera.getLastHeartbeat(),
                camera.isAutoStart(), camera.getLbsLocation() == null ? null : camera.getLbsLocation().getName());
    }
}
