package com.coltrack.events;

import java.time.Instant;
import java.util.UUID;


public record RecordingStartedEvent(

        UUID cameraId,

        UUID recordingId,

        Instant startedAt

) {}
