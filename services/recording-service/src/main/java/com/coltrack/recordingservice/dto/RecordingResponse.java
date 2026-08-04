package com.coltrack.recordingservice.dto;

import com.coltrack.recordingservice.model.RecordingEntity;
import com.coltrack.recordingservice.model.RecordingStatus;

import java.time.Instant;
import java.util.UUID;

public record RecordingResponse(

        UUID id,

        UUID cameraId,

        Instant startedAt,

        Instant finishedAt,

        Long durationSeconds,

        Long sizeBytes,

        RecordingStatus status,

        String playbackUrl

) {

    public static RecordingResponse from(
            RecordingEntity entity
    ) {

        return new RecordingResponse(

                entity.getId(),
                entity.getCameraId(),
                entity.getStartedAt(),
                entity.getFinishedAt(),
                entity.getDurationSeconds(),
                entity.getSizeBytes(),
                entity.getStatus(),

                "/recordings/" +
                        entity.getId() +
                        "/index.m3u8"

        );

    }

}
