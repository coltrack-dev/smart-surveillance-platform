package com.coltrack.searchservice.consumer;

import com.coltrack.kafka.KafkaTopics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.TopicPartition;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class DltConsumer {


    @KafkaListener(
            topics = KafkaTopics.CAMERA_EVENTS_DLT,
            groupId = "dlt-monitor"
    )
    public void consume(
            Object event
    ) {

        log.error(
                "DLT EVENT RECEIVED: {}",
                event
        );
    }

}
