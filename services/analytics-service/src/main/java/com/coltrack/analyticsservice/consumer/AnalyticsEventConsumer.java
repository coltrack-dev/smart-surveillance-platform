package com.coltrack.analyticsservice.consumer;

import com.coltrack.analyticsservice.handler.AnalyticsEventHandler;
import com.coltrack.analyticsservice.handler.AnalyticsEventHandlerRegistry;
import com.coltrack.events.analytics.AnalyticsEvent;
import com.coltrack.analyticsservice.service.AnalyticsEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AnalyticsEventConsumer {

    private final AnalyticsEventService analyticsEventService;
    private final AnalyticsEventHandlerRegistry handlerRegistry;

    @KafkaListener(
            topics = "${analytics.kafka.topic:analytics.events}",
            groupId = "${spring.kafka.consumer.group-id:analytics-service}"
    )
    public void consume(AnalyticsEvent event) {

        AnalyticsEventHandler handler = handlerRegistry.handlerFor(event);

        boolean saved =
                analyticsEventService.saveIfAbsent(event);

        if (saved) {
            handler.handle(event);

            log.info(
                    "Saved analytics event: eventId={}, cameraId={}, type={}, objectType={}",
                    event.eventId(),
                    event.cameraId(),
                    event.eventType(),
                    event.objectType()
            );
        } else {
            log.info(
                    "Skipped duplicate analytics event: eventId={}",
                    event.eventId()
            );
        }
    }
}
