package com.coltrack.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Публикуется после успешной загрузки всех частей записи
 * в объектное хранилище.
 */
public record RecordingReadyEvent(

        UUID eventId,

        Integer schemaVersion,

        String eventType,

        UUID cameraId,

        UUID recordingId,

        Instant startedAt,

        Instant finishedAt,

        Long durationSeconds,

        Instant occurredAt

) {
}
