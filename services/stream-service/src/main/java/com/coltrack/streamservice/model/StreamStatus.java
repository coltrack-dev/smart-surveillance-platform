package com.coltrack.streamservice.model;

/**
 * Current lifecycle state of camera stream.
 */
public enum StreamStatus {
    /**
     * Stream session created, FFmpeg is starting.
     */
    STARTING,

    /**
     * FFmpeg running and HLS playlist available.
     */
    RUNNING,

    /**
     * Stream lost connection, reconnect attempt in progress.
     */
    RECONNECTING,

    /**
     * Stream stop requested, FFmpeg is shutting down.
     */
    STOPPING,

    /**
     * Stream stopped normally.
     */
    STOPPED,

    /**
     * Stream failed because of error.
     */
    ERROR
}
