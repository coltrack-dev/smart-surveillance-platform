package com.coltrack.analyticsservice.handler;

import com.coltrack.events.analytics.AnalyticsEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class ObjectDetectedEventHandler implements AnalyticsEventHandler {

    public static final String EVENT_TYPE = "OBJECT_DETECTED";

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    @Override
    public void handle(AnalyticsEvent event) {
        log.info(
                "Object detected: camera={}, track={}, objectType={}, confidence={}, attributes={}",
                event.cameraId(),
                event.trackId(),
                event.objectType(),
                event.confidence(),
                event.attributes()
        );
    }
}
