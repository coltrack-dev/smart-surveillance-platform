package com.coltrack.analyticsservice.repository;

import com.coltrack.analyticsservice.entity.AnalyticsJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface AnalyticsJobRepository
        extends JpaRepository<AnalyticsJobEntity, UUID> {

    /**
     * Последняя задача realtime-анализа для камеры независимо от статуса.
     */
    Optional<AnalyticsJobEntity>
    findFirstByCameraIdAndJobTypeOrderByCreatedAtDesc(
            UUID cameraId,
            String jobType
    );

    /**
     * Последняя задача realtime-анализа камеры с одним из указанных статусов.
     * Используется для защиты от повторного запуска активного анализа.
     */
    Optional<AnalyticsJobEntity>
    findFirstByCameraIdAndJobTypeAndStatusInOrderByCreatedAtDesc(
            UUID cameraId,
            String jobType,
            Collection<String> statuses
    );

    /**
     * Последняя задача анализа конкретной записи независимо от статуса.
     * Используется для отображения результата анализа в архиве.
     */
    Optional<AnalyticsJobEntity>
    findFirstByRecordingIdAndJobTypeOrderByCreatedAtDesc(
            UUID recordingId,
            String jobType
    );

    /**
     * Активная задача анализа конкретной записи.
     * Не позволяет одновременно запустить повторный анализ одной записи.
     */
    Optional<AnalyticsJobEntity>
    findFirstByRecordingIdAndJobTypeAndStatusInOrderByCreatedAtDesc(
            UUID recordingId,
            String jobType,
            Collection<String> statuses
    );
}
