package com.coltrack.events.analytics;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record AnalyticsProfile(
        String model,
        List<Integer> classes,
        BigDecimal confidence,
        String devicePreference,
        BigDecimal linePosition,
        List<AnalyticsLine> lines,
        BigDecimal targetFps,
        Map<String, Object> attributes
) {
}
