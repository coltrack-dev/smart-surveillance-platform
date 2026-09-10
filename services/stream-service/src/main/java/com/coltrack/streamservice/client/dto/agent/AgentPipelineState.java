package com.coltrack.streamservice.client.dto.agent;

public enum AgentPipelineState {
    STARTING,
    RUNNING,
    RECONNECTING,
    STOPPING,
    STOPPED,
    FAILED
}
