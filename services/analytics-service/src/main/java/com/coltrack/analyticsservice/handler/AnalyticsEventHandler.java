package com.coltrack.analyticsservice.handler;

import com.coltrack.events.analytics.AnalyticsEvent;

public interface AnalyticsEventHandler {

    String eventType();

    void handle(AnalyticsEvent event);
}
