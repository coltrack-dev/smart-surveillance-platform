package com.coltrack.streamservice.client.dto.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentPipelineStatus(
        String agentId,
        UUID commandId,
        UUID cameraId,
        AgentPipelineState state,
        Long pid,
        int restartCount,
        AgentProbeInfo probe,
        String outputUrl,
        String lastError,
        long updatedAtEpochMs
) {
}
