package com.coltrack.analyticsservice.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class AnalyticsKafkaTopicConfiguration {

    @Bean
    public NewTopic analyticsJobsTopic() {
        return TopicBuilder.name("analytics.jobs").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic analyticsJobStatusTopic() {
        return TopicBuilder.name("analytics.job-status").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic analyticsWorkerHeartbeatTopic() {
        return TopicBuilder.name("analytics.worker-heartbeat").partitions(1).replicas(1).build();
    }
}
