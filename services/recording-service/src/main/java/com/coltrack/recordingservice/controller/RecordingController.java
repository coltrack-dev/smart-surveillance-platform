package com.coltrack.recordingservice.controller;

import com.coltrack.recordingservice.model.RecordingSession;
import com.coltrack.recordingservice.service.RecordingManager;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/recordings")
@RequiredArgsConstructor
public class RecordingController {

    private final RecordingManager recordingManager;

    @PostMapping("/{cameraId}/start")
    public RecordingSession start(
            @PathVariable UUID cameraId
    ) {

        return recordingManager.start(cameraId);
    }


    @PostMapping("/{cameraId}/stop")
    public void stop(
            @PathVariable UUID cameraId
    ) {

        recordingManager.stop(cameraId);
    }


    @GetMapping("/{cameraId}")
    public RecordingSession find(
            @PathVariable UUID cameraId
    ) {

        return recordingManager.find(cameraId);
    }
}
