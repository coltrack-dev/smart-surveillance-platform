package com.coltrack.streamservice.worker;

import com.coltrack.streamservice.model.StreamSession;

/**
 * Listener for stream lifecycle events.
 * CameraStreamWorker notifies about stream state changes.
 */
public interface StreamListener {

    /**
     * Called when FFmpeg successfully started
     * and HLS playlist was created.
     */
    void started(StreamSession session);


    /**
     * Called when FFmpeg process stopped normally.
     */
    void stopped(StreamSession session);


    /**
     * Called when stream failed.
     */
    void failed(StreamSession session);


    /**
     * Called before reconnect attempt.
     */
    void reconnecting(StreamSession session);
}
