package com.coltrack.streamservice.model;


import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;

import java.time.Instant;
import java.util.UUID;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StreamSession {


    private UUID cameraId;


    /**
     * Источник RTSP камеры.
     */
    private String rtspUrl;


    /**
     * Текущее состояние потока.
     */
    private StreamStatus status;


    /**
     * Когда поток был запущен.
     */
    private Instant startedAt;


    /**
     * Время последнего полученного кадра.
     */
    private Instant lastFrameTime;


    /**
     * URL HLS потока.
     */
    private String hlsUrl;


    /**
     * Запущенный процесс FFmpeg.
     */
    @JsonIgnore
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
