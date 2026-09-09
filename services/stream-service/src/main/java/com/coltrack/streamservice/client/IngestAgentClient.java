package com.coltrack.streamservice.client;

import com.coltrack.streamservice.client.dto.agent.AgentPipelineState;
import com.coltrack.streamservice.client.dto.agent.AgentPipelineStatus;
import com.coltrack.streamservice.client.dto.agent.AgentStartPipelineRequest;
import com.coltrack.streamservice.config.IngestAgentProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

@Component
public class IngestAgentClient {

    private final RestClient restClient;
    private final String token;

    public IngestAgentClient(
            RestClient.Builder builder,
            IngestAgentProperties properties
    ) {
        this.restClient = builder
                .baseUrl(properties.getBaseUrl())
                .build();
        this.token = properties.getToken();
    }

    public AgentPipelineStatus start(AgentStartPipelineRequest request) {
        try {
            return postStart(request);
        } catch (HttpClientErrorException.Conflict conflict) {
            // A stream-service restart can leave a healthy pipeline in the agent.
            // Reconcile with that pipeline instead of creating a duplicate FFmpeg.
            AgentPipelineStatus existing = get(request.cameraId());
            if (existing.state() != AgentPipelineState.FAILED) {
                return existing;
            }

            // Failed pipelines remain registered until DELETE. Remove the stale
            // entry so a later manual retry can create a fresh FFmpeg process.
            stop(request.cameraId());
            return postStart(request);
        }
    }

    private AgentPipelineStatus postStart(AgentStartPipelineRequest request) {
        AgentPipelineStatus status = restClient.post()
                .uri("/v1/pipelines")
                .headers(authentication())
                .body(request)
                .retrieve()
                .body(AgentPipelineStatus.class);
        return Objects.requireNonNull(status, "ingest agent returned an empty start response");
    }

    public AgentPipelineStatus get(UUID cameraId) {
        AgentPipelineStatus status = restClient.get()
                .uri("/v1/pipelines/{cameraId}", cameraId)
                .headers(authentication())
                .retrieve()
                .body(AgentPipelineStatus.class);
        return Objects.requireNonNull(status, "ingest agent returned an empty status response");
    }

    public AgentPipelineStatus stop(UUID cameraId) {
        try {
            AgentPipelineStatus status = restClient.delete()
                    .uri("/v1/pipelines/{cameraId}", cameraId)
                    .headers(authentication())
                    .retrieve()
                    .body(AgentPipelineStatus.class);
            return Objects.requireNonNull(status, "ingest agent returned an empty stop response");
        } catch (HttpClientErrorException.NotFound notFound) {
            return null;
        }
    }

    private Consumer<HttpHeaders> authentication() {
        return headers -> {
            if (StringUtils.hasText(token)) {
                headers.setBearerAuth(token);
            }
        };
    }
}
