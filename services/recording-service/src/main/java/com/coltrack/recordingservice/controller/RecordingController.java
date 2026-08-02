package com.coltrack.recordingservice.controller;

import com.coltrack.recordingservice.model.RecordingSession;
import com.coltrack.recordingservice.service.RecordingManager;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/recordings")
@RequiredArgsConstructor
public class RecordingController {


    private final RecordingManager recordingManager;


    /**
     * Manual recording start.
     */
    @PostMapping("/{cameraId}/start")
    public RecordingSession start(
            @PathVariable UUID cameraId
    ) {

        return recordingManager.start(
                cameraId,
                Instant.now()
        );
    }


    /**
     * Manual recording stop.
     */
    @PostMapping("/{cameraId}/stop")
    public void stop(
            @PathVariable UUID cameraId
    ) {

        recordingManager.stop(
                cameraId,
                Instant.now()
        );
    }


    /**
     * Get active recording session.
     */
    @GetMapping("/{cameraId}")
    public RecordingSession find(
            @PathVariable UUID cameraId
    ) {

        return recordingManager.find(cameraId);
    }
}
