package com.coltrack.analyticsservice.controller;

import com.coltrack.analyticsservice.dto.AnalyticsEventResponse;
import com.coltrack.analyticsservice.service.AnalyticsEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/analytics/events")
@RequiredArgsConstructor
public class AnalyticsEventController {

    private final AnalyticsEventService service;

    @GetMapping
    public Page<AnalyticsEventResponse> findEvents(
            @RequestParam(required = false) String cameraId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String objectType,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @PageableDefault(
                    size = 20,
                    sort = "occurredAt",
                    direction = Sort.Direction.DESC
            ) Pageable pageable
    ) {
        return service.findEvents(
                cameraId,
                eventType,
                objectType,
                from,
                to,
                pageable
        );
    }

    @GetMapping("/{eventId}")
    public AnalyticsEventResponse findById(@PathVariable UUID eventId) {
        return service.findById(eventId);
    }
}
