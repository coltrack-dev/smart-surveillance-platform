package com.coltrack.analyticsservice.exception;

public class UnsupportedAnalyticsEventTypeException extends RuntimeException {

    public UnsupportedAnalyticsEventTypeException(String eventType) {
        super(
                "No analytics event handler registered for eventType="
                        + eventType
        );
    }
}
