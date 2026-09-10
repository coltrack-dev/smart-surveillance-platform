package com.coltrack.streamservice.client.dto.agent;

public record AgentOutput(
        String type,
        String url
) {
    public static AgentOutput rtsp(String url) {
        return new AgentOutput("RTSP", url);
    }
}
