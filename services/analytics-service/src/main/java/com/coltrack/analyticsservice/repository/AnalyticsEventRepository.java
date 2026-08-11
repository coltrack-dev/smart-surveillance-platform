package com.coltrack.analyticsservice.repository;

import com.coltrack.analyticsservice.entity.AnalyticsEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AnalyticsEventRepository extends JpaRepository<AnalyticsEventEntity, UUID> {
}
