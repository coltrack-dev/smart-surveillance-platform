package com.coltrack.analyticsservice.consumer;

import com.coltrack.analyticsservice.service.AnalyticsControlService;
import com.coltrack.events.analytics.AnalyticsJobStatusEvent;
import com.coltrack.events.analytics.AnalyticsWorkerHeartbeatEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AnalyticsControlEventConsumer {

    private final ObjectMapper objectMapper;
    private final AnalyticsControlService controlService;

    @KafkaListener(
            topics = "${analytics.kafka.job-status-topic:analytics.job-status}",
            groupId = "${analytics.kafka.control-group-id:analytics-service-control}",
            containerFactory = "analyticsControlKafkaListenerContainerFactory"
    )
    public void consumeJobStatus(String payload) throws JsonProcessingException {
        AnalyticsJobStatusEvent event = objectMapper.readValue(
                payload, AnalyticsJobStatusEvent.class
        );
        controlService.applyStatus(event);
        log.info(
                "Applied analytics job status jobId={} workerId={} status={}",
                event.jobId(), event.workerId(), event.status()
        );
    }

    @KafkaListener(
            topics = "${analytics.kafka.worker-heartbeat-topic:analytics.worker-heartbeat}",
            groupId = "${analytics.kafka.control-group-id:analytics-service-control}",
            containerFactory = "analyticsControlKafkaListenerContainerFactory"
    )
    public void consumeWorkerHeartbeat(String payload) throws JsonProcessingException {
        AnalyticsWorkerHeartbeatEvent event = objectMapper.readValue(
                payload, AnalyticsWorkerHeartbeatEvent.class
        );
        controlService.applyHeartbeat(event);
        log.debug(
                "Applied analytics worker heartbeat workerId={} activeJobs={}/{}",
                event.workerId(), event.activeJobs(), event.maxJobs()
        );
    }
}
