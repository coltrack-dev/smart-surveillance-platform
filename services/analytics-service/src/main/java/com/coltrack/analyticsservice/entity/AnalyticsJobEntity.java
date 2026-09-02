package com.coltrack.analyticsservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "analytics_jobs")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalyticsJobEntity {

    @Id
    @Column(name = "job_id", nullable = false, updatable = false)
    private UUID jobId;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "camera_id", nullable = false)
    private UUID cameraId;

    @Column(name = "job_type", nullable = false, length = 32)
    private String jobType;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "worker_id", length = 200)
    private String workerId;

    @Column(name = "source_url", columnDefinition = "text")
    private String sourceUrl;

    @Column(name = "source_transport", length = 16)
    private String sourceTransport;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "profile", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> profile = new HashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> details = new HashMap<>();

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(name = "recording_id")
    private UUID recordingId;
}
