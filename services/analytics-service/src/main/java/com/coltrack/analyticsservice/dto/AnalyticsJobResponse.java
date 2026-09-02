package com.coltrack.analyticsservice.dto;

import com.coltrack.analyticsservice.entity.AnalyticsJobEntity;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record AnalyticsJobResponse(
        UUID jobId,
        UUID cameraId,

        // Для REALTIME-задач значение null,
        // для анализа записи содержит идентификатор записи.
        UUID recordingId,

        String jobType,
        String status,
        String workerId,
        String sourceUrl,
        String sourceTransport,
        Map<String, Object> profile,
        Map<String, Object> details,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt
) {

    public static AnalyticsJobResponse fromEntity(
            AnalyticsJobEntity entity
    ) {
        return new AnalyticsJobResponse(
                entity.getJobId(),
                entity.getCameraId(),
                entity.getRecordingId(),
                entity.getJobType(),
                entity.getStatus(),
                entity.getWorkerId(),
                redactCredentials(entity.getSourceUrl()),
                entity.getSourceTransport(),
                entity.getProfile(),
                entity.getDetails(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getStartedAt(),
                entity.getFinishedAt()
        );
    }

    private static String redactCredentials(String sourceUrl) {
        if (sourceUrl == null) {
            return null;
        }
        return sourceUrl
                .replaceAll("(?i)(rtsp[s]?://[^:/\\s]+:)[^@\\s]+@", "$1***@")
                .replaceAll("(?i)(_password=)[^_/?&\\s]+", "$1***");
    }
}
