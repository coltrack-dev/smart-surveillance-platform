package com.coltrack.analyticsservice.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RecordingAnalyticsStartRequest(
        UUID cameraId,
        String model,
        List<Integer> classes,
        BigDecimal confidence,
        String devicePreference,
        BigDecimal linePosition,
        BigDecimal targetFps,
        Map<String, Object> attributes
) {
}
