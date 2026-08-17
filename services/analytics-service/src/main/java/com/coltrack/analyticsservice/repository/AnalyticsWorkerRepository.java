package com.coltrack.analyticsservice.repository;

import com.coltrack.analyticsservice.entity.AnalyticsWorkerEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalyticsWorkerRepository extends JpaRepository<AnalyticsWorkerEntity, String> {
}
