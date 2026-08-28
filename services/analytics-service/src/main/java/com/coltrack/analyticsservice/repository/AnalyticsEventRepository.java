package com.coltrack.analyticsservice.repository;

import com.coltrack.analyticsservice.entity.AnalyticsEventEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

public interface AnalyticsEventRepository
        extends JpaRepository<AnalyticsEventEntity, UUID>,
        JpaSpecificationExecutor<AnalyticsEventEntity> {

    Page<AnalyticsEventEntity> findAllByRecordingId(
            UUID recordingId,
            Pageable pageable
    );
}
