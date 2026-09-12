package com.coltrack.recordingservice.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "recording_sessions",
        indexes = {
                @Index(
                        name = "idx_recording_sessions_camera_started_at",
                        columnList = "camera_id, started_at"
                ),
                @Index(
                        name = "idx_recording_cleanup_candidates",
                        columnList = "protected_from_deletion, status, cleanup_status, finished_at"
                )
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecordingEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID cameraId;

    /**
     * Directory or first segment path.
     */
    @Column(nullable = false)
    private String filePath;

    /**
     * Recording start time.
     */
    private Instant startedAt;

    /**
     * Recording finish time.
     */
    private Instant finishedAt;

    /**
     * Calculated recording duration.
     */
    private Long durationSeconds;

    /**
     * Total size of generated files.
     */
    private Long sizeBytes;

    /**
     * Number of generated segments.
     */
    private Integer segmentsCount;

    /**
     * FFmpeg exit code.
     */
    private Integer exitCode;

    /**
     * Video metadata.
     */
    private Integer width;

    private Integer height;

    private Integer fps;

    private String codec;

    @Enumerated(EnumType.STRING)
    private RecordingStatus status;

    /**
     * Error message or stop reason.
     */
    @Column(length = 2000)
    private String reason;

    /**
     * Protected recordings are never eligible for retention cleanup.
     */
    @Builder.Default
    @Column(
            name = "protected_from_deletion",
            nullable = false
    )
    @ColumnDefault("false")
    private boolean protectedFromDeletion = false;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(
            name = "cleanup_status",
            nullable = false,
            length = 32
    )
    @ColumnDefault("'AVAILABLE'")
    private RecordingCleanupStatus cleanupStatus =
            RecordingCleanupStatus.AVAILABLE;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deletion_reason", length = 1000)
    private String deletionReason;
}
