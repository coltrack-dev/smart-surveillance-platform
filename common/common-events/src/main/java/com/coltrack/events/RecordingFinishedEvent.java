package com.coltrack.events;

import java.time.Instant;
import java.util.UUID;


public record RecordingFinishedEvent(

        UUID cameraId,

        UUID recordingId,

        Instant startedAt,

        Instant finishedAt

) {}
