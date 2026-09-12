package com.coltrack.recordingservice.service;

import com.coltrack.recordingservice.config.RecordingStoragePolicyProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class RecordingStorageGuard {

    private final RecordingStorageService recordingStorageService;
    private final RecordingStoragePolicyProperties policy;

    public void assertRecordingCanStart() {
        RecordingStorageService.StorageSnapshot snapshot =
                recordingStorageService.getSnapshot();
        long maximumLocalBytes = policy.getMaximumLocalSize().toBytes();
        double freePercent = snapshot.totalBytes() == 0
                ? 0
                : snapshot.usableBytes() * 100.0 / snapshot.totalBytes();

        if (snapshot.recordingBytes() >= maximumLocalBytes) {
            throw insufficientStorage(
                    "Local recording storage limit reached"
            );
        }
        if (freePercent <= policy.getEmergencyFreePercent()) {
            throw insufficientStorage(
                    "Recording storage is below emergency free-space threshold"
            );
        }
    }

    private ResponseStatusException insufficientStorage(String message) {
        return new ResponseStatusException(
                HttpStatus.INSUFFICIENT_STORAGE,
                message
        );
    }
}
