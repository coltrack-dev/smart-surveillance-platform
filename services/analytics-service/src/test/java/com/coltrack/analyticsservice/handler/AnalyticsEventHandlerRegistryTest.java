package com.coltrack.analyticsservice.handler;

import com.coltrack.analyticsservice.exception.UnsupportedAnalyticsEventTypeException;
import com.coltrack.events.analytics.AnalyticsEvent;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalyticsEventHandlerRegistryTest {

    @Test
    void shouldDispatchEventToMatchingHandler() {
        AnalyticsEventHandler handler = handler("LINE_CROSSED");
        AnalyticsEventHandlerRegistry registry = new AnalyticsEventHandlerRegistry(List.of(handler));
        AnalyticsEvent event = event("LINE_CROSSED");

        registry.handle(event);

        verify(handler).handle(event);
    }

    @Test
    void shouldRejectUnsupportedEventType() {
        AnalyticsEventHandlerRegistry registry = new AnalyticsEventHandlerRegistry(
                List.of(handler("LINE_CROSSED"))
        );

        assertThatThrownBy(() -> registry.handle(event("OBJECT_DETECTED")))
                .isInstanceOf(UnsupportedAnalyticsEventTypeException.class)
                .hasMessageContaining("OBJECT_DETECTED");
    }

    @Test
    void shouldRejectDuplicateHandlerRegistration() {
        AnalyticsEventHandler first = handler("LINE_CROSSED");
        AnalyticsEventHandler second = handler("LINE_CROSSED");

        assertThatThrownBy(() -> new AnalyticsEventHandlerRegistry(List.of(first, second)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Multiple analytics handlers")
                .hasMessageContaining("LINE_CROSSED");
    }

    private AnalyticsEventHandler handler(String eventType) {
        AnalyticsEventHandler handler = mock(AnalyticsEventHandler.class);
        when(handler.eventType()).thenReturn(eventType);
        return handler;
    }

    private AnalyticsEvent event(String eventType) {
        return new AnalyticsEvent(
                UUID.randomUUID(), 1, eventType, "camera-1",
                10L, "PERSON", null, 25L, null,
                OffsetDateTime.now(), Map.of("direction", "UP")
        );
    }
}
