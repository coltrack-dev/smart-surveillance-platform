package com.coltrack.analyticsservice.consumer;

import com.coltrack.events.analytics.AnalyticsEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class AnalyticsEventConsumer {

    @KafkaListener(
            topics = "${analytics.kafka.topic:analytics.events}",
            groupId = "${spring.kafka.consumer.group-id:analytics-service}"
    )
    public void consume(AnalyticsEvent event) {
        log.info(
                "Received analytics event: eventId={}, cameraId={}, " +
                        "type={}, objectType={}, direction={}",
                event.eventId(),
                event.cameraId(),
                event.eventType(),
                event.objectType(),
                event.direction()
        );
    }
}
