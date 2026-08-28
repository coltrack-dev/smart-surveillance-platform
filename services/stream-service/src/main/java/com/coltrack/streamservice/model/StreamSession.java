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

    @Builder.Default
    private VideoProcessingMode videoProcessingMode = VideoProcessingMode.AUTO;

    private String detectedVideoCodec;


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

    private volatile boolean stopRequested;

    /**
     * Indicates that the CameraStreamWorker lifecycle is still active.
     *
     * This flag is intentionally independent from the FFmpeg process state:
     * between reconnect attempts FFmpeg is not running, but the worker still
     * owns the camera session and will start a new process. StreamManager uses
     * it to prevent a second worker from being created during that interval.
     */
    @JsonIgnore
    private volatile boolean workerRunning;

    public boolean isRunning() {
        return status == StreamStatus.RUNNING;
    }

    public StreamStatus getSafeStatus() {

        return status == null
                ? StreamStatus.STOPPED
                : status;
    }
}
