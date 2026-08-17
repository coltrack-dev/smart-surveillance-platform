package com.coltrack.analyticsservice.repository;

import com.coltrack.analyticsservice.entity.AnalyticsJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface AnalyticsJobRepository extends JpaRepository<AnalyticsJobEntity, UUID> {

    Optional<AnalyticsJobEntity> findFirstByCameraIdAndJobTypeOrderByCreatedAtDesc(
            UUID cameraId,
            String jobType
    );

    Optional<AnalyticsJobEntity> findFirstByCameraIdAndJobTypeAndStatusInOrderByCreatedAtDesc(
            UUID cameraId,
            String jobType,
            Collection<String> statuses
    );
}
