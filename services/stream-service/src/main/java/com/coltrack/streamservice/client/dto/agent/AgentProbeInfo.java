package com.coltrack.streamservice.client.dto.agent;

public record AgentProbeInfo(
        String codec,
        int width,
        int height,
        Double fps
) {
}
