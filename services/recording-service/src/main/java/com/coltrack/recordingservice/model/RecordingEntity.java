package com.coltrack.recordingservice.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "recording_sessions")
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
}
