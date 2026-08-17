package com.coltrack.analyticsservice.handler;

import com.coltrack.events.analytics.AnalyticsEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ObjectDetectedEventHandlerTest {

    private final ObjectDetectedEventHandler handler = new ObjectDetectedEventHandler();

    @Test
    void shouldSupportObjectDetectedEvents() {
        assertThat(handler.eventType()).isEqualTo("OBJECT_DETECTED");
    }

    @Test
    void shouldHandleObjectDetectedEvent() {
        AnalyticsEvent event = new AnalyticsEvent(
                UUID.randomUUID(),
                1,
                "OBJECT_DETECTED",
                "camera-1",
                null,
                42L,
                "PERSON",
                new BigDecimal("0.92345"),
                100L,
                new BigDecimal("3.333"),
                OffsetDateTime.now(),
                Map.of("zoneId", "entrance")
        );

        assertThatCode(() -> handler.handle(event)).doesNotThrowAnyException();
    }
}
