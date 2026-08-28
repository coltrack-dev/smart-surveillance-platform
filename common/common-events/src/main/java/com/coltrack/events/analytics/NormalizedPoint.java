package com.coltrack.events.analytics;

import java.math.BigDecimal;

public record NormalizedPoint(
        BigDecimal x,
        BigDecimal y
) {
}
