package com.coltrack.recordingservice.dto;

import com.coltrack.recordingservice.model.RecordingSession;
import com.coltrack.recordingservice.model.RecordingStatus;

import java.time.Instant;
import java.util.UUID;

public record ActiveRecordingResponse(
        UUID id,
        UUID cameraId,
        RecordingStatus status,
        Instant startedAt,
        Instant finishedAt,
        String lastError,
        Long durationSeconds,
        Long sizeBytes
) {

    public static ActiveRecordingResponse from(RecordingSession session) {
        if (session == null) {
            return null;
        }

        return new ActiveRecordingResponse(
                session.getId(),
                session.getCameraId(),
                session.getStatus(),
                session.getStartedAt(),
                session.getFinishedAt(),
                session.getLastError(),
                session.getDurationSeconds(),
                session.getSizeBytes()
        );
    }
}
