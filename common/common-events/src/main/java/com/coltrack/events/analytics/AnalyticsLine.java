package com.coltrack.events.analytics;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record AnalyticsLine(
        String id,
        NormalizedPoint start,
        NormalizedPoint end,
        String anchor,
        List<String> allowedDirections,
        Map<String, String> directionLabels,
        List<Integer> allowedClasses,
        BigDecimal cooldownSeconds,
        BigDecimal hysteresis,
        Integer minimumTrackAgeFrames
) {
}
