package com.coltrack.analyticsservice.dto;

public record AnalyticsEventPagePositionResponse(
        int page,
        long precedingEvents
) {
}
