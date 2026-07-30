package com.coltrack.recordingservice.kafka;

import com.coltrack.kafka.KafkaTopics;
import lombok.extern.slf4j.Slf4j;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;


@Slf4j
@Component
public class StreamEventConsumer {


    @KafkaListener(
            topics = KafkaTopics.STREAM_EVENTS,
            groupId = "recording-service"
    )
    public void consume(
            String message
    ) {


        log.info(
                "Received stream event {}",
                message
        );

    }
}
