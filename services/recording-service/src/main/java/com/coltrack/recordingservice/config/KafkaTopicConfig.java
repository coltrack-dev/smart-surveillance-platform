package com.coltrack.recordingservice.config;

import com.coltrack.kafka.KafkaTopics;

import org.apache.kafka.clients.admin.NewTopic;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic recordingEventsTopic() {

        return TopicBuilder
                .name(
                        KafkaTopics.RECORDING_EVENTS
                )
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic analyticsEventsTopic() {

        return TopicBuilder
                .name(
                        KafkaTopics.ANALYTICS_EVENTS
                )
                .partitions(3)
                .replicas(1)
                .build();
    }
}
