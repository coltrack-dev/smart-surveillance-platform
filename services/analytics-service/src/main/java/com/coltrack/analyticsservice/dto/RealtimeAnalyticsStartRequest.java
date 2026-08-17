package com.coltrack.analyticsservice.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record RealtimeAnalyticsStartRequest(
        String sourceUrl,
        String transport,
        String model,
        List<Integer> classes,
        BigDecimal confidence,
        String devicePreference,
        BigDecimal linePosition,
        BigDecimal targetFps,
        Map<String, Object> attributes
) {
}
