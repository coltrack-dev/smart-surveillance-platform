package com.coltrack.recordingservice.controller;

import com.coltrack.recordingservice.dto.RecordingDateResponse;
import com.coltrack.recordingservice.dto.RecordingResponse;
import com.coltrack.recordingservice.dto.ActiveRecordingResponse;
import com.coltrack.recordingservice.dto.RecordingPageResponse;
import com.coltrack.recordingservice.dto.RecordingProtectionRequest;
import com.coltrack.recordingservice.dto.RecordingStorageStatusResponse;
import com.coltrack.recordingservice.client.StreamClient;
import com.coltrack.recordingservice.model.RecordingStatus;
import com.coltrack.recordingservice.service.RecordingManager;
import com.coltrack.recordingservice.service.RecordingQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/recordings")
@RequiredArgsConstructor
@Validated
public class RecordingController {


    private final RecordingManager recordingManager;
    private final RecordingQueryService recordingQueryService;
    private final StreamClient streamClient;

    @GetMapping
    public RecordingPageResponse findRecordings(
            @RequestParam(required = false) UUID cameraId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) List<RecordingStatus> statuses,
            @RequestParam(name = "protected", required = false) Boolean protectedFromDeletion,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return recordingQueryService.find(
                cameraId,
                from,
                to,
                statuses,
                protectedFromDeletion,
                page,
                size
        );
    }

    @GetMapping("/storage")
    public RecordingStorageStatusResponse getStorageStatus() {
        return recordingQueryService.getStorageStatus();
    }

    @PatchMapping("/{recordingId}/protection")
    public RecordingResponse setProtection(
            @PathVariable UUID recordingId,
            @Valid @RequestBody RecordingProtectionRequest request
    ) {
        return recordingQueryService.setProtected(
                recordingId,
                request.protectedFromDeletion()
        );
    }


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
