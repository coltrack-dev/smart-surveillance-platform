package com.coltrack.analyticsservice.handler;

import com.coltrack.analyticsservice.exception.UnsupportedAnalyticsEventTypeException;
import com.coltrack.events.analytics.AnalyticsEvent;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class AnalyticsEventHandlerRegistry {

    private final Map<String, AnalyticsEventHandler> handlers;

    public AnalyticsEventHandlerRegistry(List<AnalyticsEventHandler> handlers) {
        Map<String, AnalyticsEventHandler> registeredHandlers = new HashMap<>();

        for (AnalyticsEventHandler handler : handlers) {
            String eventType = Objects.requireNonNull(
                    handler.eventType(),
                    () -> "Handler " + handler.getClass().getName() + " returned null eventType"
            );

            if (eventType.isBlank()) {
                throw new IllegalStateException(
                        "Handler " + handler.getClass().getName() + " returned blank eventType"
                );
            }

            AnalyticsEventHandler existing = registeredHandlers.putIfAbsent(eventType, handler);
            if (existing != null) {
                throw new IllegalStateException(
                        "Multiple analytics handlers registered for eventType=" + eventType
                                + ": " + existing.getClass().getName()
                                + " and " + handler.getClass().getName()
                );
            }
        }

        this.handlers = Map.copyOf(registeredHandlers);
    }

    public void handle(AnalyticsEvent event) {
        handlerFor(event).handle(event);
    }

    public AnalyticsEventHandler handlerFor(AnalyticsEvent event) {
        Objects.requireNonNull(event, "event must not be null");

        AnalyticsEventHandler handler = handlers.get(event.eventType());
        if (handler == null) {
            throw new UnsupportedAnalyticsEventTypeException(event.eventType());
        }
        return handler;
    }
}
