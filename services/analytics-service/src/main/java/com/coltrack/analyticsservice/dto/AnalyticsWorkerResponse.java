package com.coltrack.analyticsservice.dto;

import com.coltrack.analyticsservice.entity.AnalyticsWorkerEntity;

import java.time.Duration;
import java.time.OffsetDateTime;

public record AnalyticsWorkerResponse(
        String workerId,
        String status,
        boolean online,
        Integer activeJobs,
        Integer maxJobs,
        String host,
        String platform,
        Boolean cudaAvailable,
        Integer cudaDeviceCount,
        String gpuName,
        OffsetDateTime lastSeenAt
) {
    public static AnalyticsWorkerResponse fromEntity(
            AnalyticsWorkerEntity entity,
            OffsetDateTime now,
            Duration onlineTimeout
    ) {
        boolean online = entity.getLastSeenAt() != null
                && entity.getLastSeenAt().isAfter(now.minus(onlineTimeout));
        return new AnalyticsWorkerResponse(
                entity.getWorkerId(),
                online ? entity.getStatus() : "OFFLINE",
                online,
                entity.getActiveJobs(),
                entity.getMaxJobs(),
                entity.getHost(),
                entity.getPlatform(),
                entity.getCudaAvailable(),
                entity.getCudaDeviceCount(),
                entity.getGpuName(),
                entity.getLastSeenAt()
        );
    }
}
