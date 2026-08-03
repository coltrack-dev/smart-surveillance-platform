package com.coltrack.streamservice.worker;

import com.coltrack.streamservice.model.StreamSession;

/**
 * Listener for stream lifecycle events.
 * CameraStreamWorker notifies about stream state changes.
 */
public interface StreamListener {

    /**
     * Called when HLS stream becomes ready.
     * CameraStreamWorker invokes this method
     * after FFmpeg start and successful HLS playlist creation.
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
