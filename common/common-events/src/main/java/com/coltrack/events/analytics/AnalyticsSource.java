package com.coltrack.events.analytics;

public record AnalyticsSource(
        String type,
        String url,
        String transport
) {
}
