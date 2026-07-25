package com.coltrack.searchservice.consumer;

import com.coltrack.events.CameraRegisteredEvent;
import com.coltrack.kafka.KafkaTopics;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.TopicPartition;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class DltConsumer {


    @KafkaListener(
            topics = KafkaTopics.CAMERA_EVENTS + ".DLT",
            groupId = "dlt-monitor"
    )
    public void consume(
            ConsumerRecord<String, CameraRegisteredEvent> record
    ) {


        log.error(
                """
                DLT MESSAGE
                topic={}
                partition={}
                offset={}
                key={}
                value={}
                """,
                record.topic(),
                record.partition(),
                record.offset(),
                record.key(),
                record.value()
        );


        record.headers()
                .forEach(header ->
                        log.error(
                                "header {}={}",
                                header.key(),
                                new String(header.value())
                        )
                );
    }
}
