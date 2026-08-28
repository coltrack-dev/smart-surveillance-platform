package com.coltrack.analyticsservice.controller;

import com.coltrack.analyticsservice.dto.AnalyticsEventPagePositionResponse;
import com.coltrack.analyticsservice.dto.AnalyticsEventResponse;
import com.coltrack.analyticsservice.dto.AnalyticsJobResponse;
import com.coltrack.analyticsservice.dto.AnalyticsWorkerResponse;
import com.coltrack.analyticsservice.dto.RealtimeAnalyticsStartRequest;
import com.coltrack.analyticsservice.dto.RecordingAnalyticsStartRequest;
import com.coltrack.analyticsservice.service.AnalyticsControlService;
import com.coltrack.analyticsservice.service.AnalyticsEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AnalyticsControlController {

    private final AnalyticsControlService controlService;
    private final AnalyticsEventService eventService;

    @PostMapping("/realtime/{cameraId}/start")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AnalyticsJobResponse startRealtime(
            @PathVariable UUID cameraId,
            @RequestBody RealtimeAnalyticsStartRequest request
    ) {
        return controlService.startRealtime(cameraId, request);
    }

    @PostMapping("/realtime/{cameraId}/stop")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AnalyticsJobResponse stopRealtime(@PathVariable UUID cameraId) {
        return controlService.stopRealtime(cameraId);
    }

    @PostMapping("/recordings/{recordingId}/start")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AnalyticsJobResponse startRecording(
            @PathVariable UUID recordingId,
            @RequestBody RecordingAnalyticsStartRequest request
    ) {
        return controlService.startRecording(recordingId, request);
    }

    @PostMapping("/recordings/{recordingId}/stop")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AnalyticsJobResponse stopRecording(@PathVariable UUID recordingId) {
        return controlService.stopRecording(recordingId);
    }

    @GetMapping("/recordings/{recordingId}")
    public AnalyticsJobResponse findLatestRecordingJob(
            @PathVariable UUID recordingId
    ) {
        return controlService.findLatestRecordingJob(recordingId);
    }

    @GetMapping("/realtime/{cameraId}")
    public AnalyticsJobResponse findLatestRealtimeJob(@PathVariable UUID cameraId) {
        return controlService.findLatestRealtimeJob(cameraId);
    }

    @GetMapping("/jobs/{jobId}")
    public AnalyticsJobResponse findJob(@PathVariable UUID jobId) {
        return controlService.findJob(jobId);
    }

    @GetMapping("/jobs/{jobId}/events")
    public Page<AnalyticsEventResponse> findJobEvents(
            @PathVariable UUID jobId,
            @PageableDefault(
                    size = 50,
                    sort = "videoTimeSeconds",
                    direction = Sort.Direction.ASC
            ) Pageable pageable
    ) {
        return eventService.findEventsForJob(jobId, pageable);
    }

    @GetMapping("/jobs/{jobId}/events/page-for-time")
    public AnalyticsEventPagePositionResponse findEventPageForTime(
            @PathVariable UUID jobId,
            @RequestParam BigDecimal videoTimeSeconds,
            @RequestParam(defaultValue = "6") int size
    ) {
        return eventService.findEventPageForTime(jobId, videoTimeSeconds, size);
    }

    @GetMapping("/jobs")
    public Page<AnalyticsJobResponse> findJobs(
            @PageableDefault(
                    size = 20,
                    sort = "createdAt",
                    direction = Sort.Direction.DESC
            ) Pageable pageable
    ) {
        return controlService.findJobs(pageable);
    }

    @GetMapping("/workers")
    public List<AnalyticsWorkerResponse> findWorkers() {
        return controlService.findWorkers();
    }
}
