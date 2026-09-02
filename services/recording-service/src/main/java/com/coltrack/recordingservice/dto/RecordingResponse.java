package com.coltrack.recordingservice.dto;

import com.coltrack.recordingservice.model.RecordingEntity;
import com.coltrack.recordingservice.model.RecordingStatus;
import com.coltrack.recordingservice.model.RecordingStorageType;

import java.time.Instant;
import java.util.UUID;

public record RecordingResponse(

        UUID id,

        UUID cameraId,

        Instant startedAt,

        Instant finishedAt,

        Long durationSeconds,

        Long sizeBytes,

        Integer segmentsCount,

        Integer width,

        Integer height,

        Integer fps,

        String codec,

        RecordingStatus status,

        String reason,

        boolean protectedFromDeletion,

        RecordingStorageType storageType,

        String playbackUrl,

        String downloadUrl

) {

    public static RecordingResponse from(
            RecordingEntity entity,
            RecordingStorageType storageType
    ) {

        return new RecordingResponse(

                entity.getId(),
                entity.getCameraId(),
                entity.getStartedAt(),
                entity.getFinishedAt(),
                entity.getDurationSeconds(),
                entity.getSizeBytes(),
                entity.getSegmentsCount(),
                entity.getWidth(),
                entity.getHeight(),
                entity.getFps(),
                entity.getCodec(),
                entity.getStatus(),
                entity.getReason(),
                entity.isProtectedFromDeletion(),
                storageType,

                "/recordings/" +
                        entity.getId() +
                        "/index.m3u8",

                "/api/v1/recordings/" +
                        entity.getId() +
                        "/download"

        );

    }

    public static RecordingResponse from(RecordingEntity entity) {
        return from(entity, RecordingStorageType.MISSING);
    }

}
