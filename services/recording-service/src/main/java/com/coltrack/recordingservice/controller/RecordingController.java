package com.coltrack.recordingservice.controller;

import com.coltrack.recordingservice.dto.RecordingDateResponse;
import com.coltrack.recordingservice.dto.RecordingResponse;
import com.coltrack.recordingservice.dto.ActiveRecordingResponse;
import com.coltrack.recordingservice.client.StreamClient;
import com.coltrack.recordingservice.service.RecordingManager;
import com.coltrack.recordingservice.service.RecordingQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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
    private final StreamClient streamClient;


    /**
     * Manual recording start.
     */
    @PostMapping("/{cameraId}/start")
    public ActiveRecordingResponse start(
            @PathVariable UUID cameraId
    ) {

        /*
         * Recording is allowed to depend on a live stream, but starting a
         * stream alone must never create a recording. StreamManager.start is
         * idempotent, therefore this is also safe when LIVE is already open.
         */
        streamClient.start(cameraId);

        return ActiveRecordingResponse.from(
                recordingManager.start(
                        cameraId,
                        UUID.randomUUID(),
                        Instant.now()
                )
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
    public ResponseEntity<ActiveRecordingResponse> find(
            @PathVariable UUID cameraId
    ) {

        ActiveRecordingResponse response = ActiveRecordingResponse.from(
                recordingManager.find(cameraId)
        );

        return response == null
                ? ResponseEntity.notFound().build()
                : ResponseEntity.ok(response);
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
