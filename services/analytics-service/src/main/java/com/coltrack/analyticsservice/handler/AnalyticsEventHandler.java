package com.coltrack.analyticsservice.handler;

import com.coltrack.events.analytics.AnalyticsEvent;

public interface AnalyticsEventHandler {

    boolean supports(String eventType);

    void handle(AnalyticsEvent event);
}
