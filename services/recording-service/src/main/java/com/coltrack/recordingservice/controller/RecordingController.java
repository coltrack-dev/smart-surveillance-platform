package com.coltrack.recordingservice.controller;

import com.coltrack.recordingservice.dto.RecordingDateResponse;
import com.coltrack.recordingservice.dto.RecordingResponse;
import com.coltrack.recordingservice.model.RecordingSession;
import com.coltrack.recordingservice.service.RecordingManager;
import com.coltrack.recordingservice.service.RecordingQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/recordings")
@RequiredArgsConstructor
public class RecordingController {


    private final RecordingManager recordingManager;
    private final RecordingQueryService recordingQueryService;


    /**
     * Manual recording start.
     */
    @PostMapping("/{cameraId}/start")
    public RecordingSession start(
            @PathVariable UUID cameraId
    ) {

        return recordingManager.start(
                cameraId,
                UUID.randomUUID(),
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

    @GetMapping("/cameras/{cameraId}/dates")
    public List<RecordingDateResponse> findAvailableDates(
            @PathVariable UUID cameraId
    ) {

        return recordingQueryService.findAvailableDates(
                cameraId
        );
    }

    @GetMapping("/cameras/{cameraId}")
    public List<RecordingResponse> findByDate(
            @PathVariable UUID cameraId,
            @RequestParam LocalDate date
    ) {

        return recordingQueryService.findByDate(
                cameraId,
                date
        );
    }
}
