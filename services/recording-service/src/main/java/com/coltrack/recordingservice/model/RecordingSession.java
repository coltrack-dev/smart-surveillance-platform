package com.coltrack.recordingservice.model;

import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class RecordingSession {

    /**
     * Recording session id.
     */
    private UUID id;

    /**
     * Camera identifier.
     */
    private UUID cameraId;

    /**
     * RTSP source.
     */
    private String rtspUrl;

    /**
     * Current recording status.
     */
    private RecordingStatus status;

    /**
     * Recording start time.
     */
    private Instant startedAt;

    /**
     * Recording finish time.
     */
    private Instant finishedAt;

    /**
     * Directory containing recording segments.
     */
    private String filePath;

    /**
     * Current FFmpeg process.
     */
    private Process ffmpegProcess;

    /**
     * Indicates that recording should stop gracefully.
     */
    @Builder.Default
    private volatile boolean stopRequested = false;

    /**
     * Last error description.
     */
    private String lastError;

    /**
     * Total recording duration.
     */
    private Long durationSeconds;

    /**
     * Total recording size.
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
     * Video width.
     */
    private Integer width;

    /**
     * Video height.
     */
    private Integer height;

    /**
     * Frames per second.
     */
    private Integer fps;

    /**
     * Video codec.
     */
    private String codec;

    /**
     * S3 object key.
     */
    @Builder.Default
    private List<String> s3Keys = new ArrayList<>();

    /**
     * Upload completed successfully.
     */
    @Builder.Default
    private boolean uploaded = false;

    /**
     * Upload completion time.
     */
    private Instant uploadedAt;
}
