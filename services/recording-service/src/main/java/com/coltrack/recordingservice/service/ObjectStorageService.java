package com.coltrack.recordingservice.service;

import com.coltrack.recordingservice.model.RecordingSession;

public interface ObjectStorageService {

    void uploadRecording(RecordingSession session);

    void deleteRecording(RecordingSession session);
}
