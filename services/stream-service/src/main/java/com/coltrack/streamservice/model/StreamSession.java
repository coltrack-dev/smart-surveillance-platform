package com.coltrack.streamservice.model;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StreamSession {

    private UUID cameraId;

    private String rtspUrl;

    private StreamStatus status;

    /**
     * Когда поток был запущен.
     */
    private Instant startedAt;

    /**
     * Время получения последнего кадра.
     */
    private Instant lastFrameTime;

    /**
     * Запущенный процесс FFmpeg.
     */
    private Process ffmpegProcess;

    /**
     * Количество попыток переподключения.
     */
    private int reconnectCount;

    /**
     * Последняя ошибка.
     */
    private String lastError;

}
