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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "analytics_events")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalyticsEventEntity {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "schema_version", nullable = false)
    @Builder.Default
    private Integer schemaVersion = 1;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "camera_id", nullable = false, length = 100)
    private String cameraId;

    @Column(name = "track_id")
    private Long trackId;

    @Column(name = "object_type", length = 100)
    private String objectType;

    @Column(name = "confidence", precision = 6, scale = 5)
    private BigDecimal confidence;

    @Column(name = "frame_number")
    private Long frameNumber;

    @Column(name = "video_time_seconds", precision = 14, scale = 3)
    private BigDecimal videoTimeSeconds;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    @CreationTimestamp
    @Column(name = "received_at", nullable = false, updatable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "recording_id")
    private UUID recordingId;

    @Column(name = "snapshot_url", columnDefinition = "text")
    private String snapshotUrl;

    @Column(name = "clip_url", columnDefinition = "text")
    private String clipUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attributes", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> attributes = new HashMap<>();
}
