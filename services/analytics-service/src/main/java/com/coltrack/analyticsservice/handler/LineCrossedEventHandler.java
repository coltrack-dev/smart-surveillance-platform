package com.coltrack.analyticsservice.handler;

import com.coltrack.events.analytics.AnalyticsEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class LineCrossedEventHandler implements AnalyticsEventHandler {

    @Override
    public boolean supports(String eventType) {
        return "LINE_CROSSED".equals(eventType);
    }

    @Override
    public void handle(AnalyticsEvent event) {
        log.info(
                "Line crossed: camera={}, track={}, attributes={}",
                event.cameraId(),
                event.trackId(),
                event.attributes()
        );
    }
}
