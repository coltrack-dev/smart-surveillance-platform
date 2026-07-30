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

    private RecordingStatus status;

    private Instant startedAt;

    private Instant finishedAt;

    private String filePath;

    private Process ffmpegProcess;
}
