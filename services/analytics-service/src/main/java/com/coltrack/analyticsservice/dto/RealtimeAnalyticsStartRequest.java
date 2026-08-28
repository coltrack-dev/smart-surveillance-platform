package com.coltrack.analyticsservice.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import com.coltrack.events.analytics.AnalyticsLine;

public record RealtimeAnalyticsStartRequest(
        String sourceUrl,
        String transport,
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
