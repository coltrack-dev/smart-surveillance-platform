package com.coltrack.recordingservice.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class RecordingSession {

    private UUID id;

    private UUID cameraId;

    private String rtspUrl;

    private RecordingStatus status;

    private Instant startedAt;

    private Instant finishedAt;

    private String filePath;

    private Process ffmpegProcess;

    /**
     * Indicates that recording should stop gracefully.
     */
    @Builder.Default
    private volatile boolean stopRequested = false;


    /**
     * Error description if recording failed.
     */
    private String lastError;
}
