package com.coltrack.streamservice.client.dto.agent;

import java.util.UUID;

public record AgentStartPipelineRequest(
        UUID commandId,
        UUID cameraId,
        String rtspUrl,
        String transport,
        String videoMode,
        AgentOutput output,
        boolean reconnect
) {
}
