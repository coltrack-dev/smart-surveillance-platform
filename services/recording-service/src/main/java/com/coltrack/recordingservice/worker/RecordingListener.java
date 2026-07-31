package com.coltrack.recordingservice.worker;
import com.coltrack.recordingservice.model.RecordingSession;

/**
 * Listener for recording lifecycle events.
 *
 * RecordingWorker notifies about recording state changes.
 */
public interface RecordingListener {

    /**
     * Called when FFmpeg recording started successfully.
     */
    void started(RecordingSession session);

    /**
     * Called when recording stopped normally.
     */
    void stopped(RecordingSession session);

    /**
     * Called when recording failed.
     */
    void failed(RecordingSession session);
}
