package com.coltrack.analyticsservice.repository;

import com.coltrack.analyticsservice.entity.AnalyticsJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface AnalyticsJobRepository
        extends JpaRepository<AnalyticsJobEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from AnalyticsJobEntity job where job.jobId = :jobId")
    Optional<AnalyticsJobEntity> findByIdForUpdate(@Param("jobId") UUID jobId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select job from AnalyticsJobEntity job
            where job.cameraId = :cameraId
              and job.jobType = :jobType
              and job.status in :statuses
            """)
    Optional<AnalyticsJobEntity> findActiveCameraJobForUpdate(
            @Param("cameraId") UUID cameraId,
            @Param("jobType") String jobType,
            @Param("statuses") Collection<String> statuses
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select job from AnalyticsJobEntity job
            where job.recordingId = :recordingId
              and job.jobType = :jobType
              and job.status in :statuses
            """)
    Optional<AnalyticsJobEntity> findActiveRecordingJobForUpdate(
            @Param("recordingId") UUID recordingId,
            @Param("jobType") String jobType,
            @Param("statuses") Collection<String> statuses
    );

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
