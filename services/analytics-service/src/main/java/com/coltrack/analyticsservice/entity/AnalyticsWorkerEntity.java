package com.coltrack.analyticsservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "analytics_workers")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalyticsWorkerEntity {

    @Id
    @Column(name = "worker_id", nullable = false, length = 200)
    private String workerId;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "active_jobs", nullable = false)
    private Integer activeJobs;

    @Column(name = "max_jobs", nullable = false)
    private Integer maxJobs;

    @Column(name = "host", length = 255)
    private String host;

    @Column(name = "platform", columnDefinition = "text")
    private String platform;

    @Column(name = "cuda_available", nullable = false)
    private Boolean cudaAvailable;

    @Column(name = "cuda_device_count", nullable = false)
    private Integer cudaDeviceCount;

    @Column(name = "gpu_name", length = 255)
    private String gpuName;

    @Column(name = "last_seen_at", nullable = false)
    private OffsetDateTime lastSeenAt;
}
