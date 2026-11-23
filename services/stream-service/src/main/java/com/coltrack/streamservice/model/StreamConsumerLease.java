package com.coltrack.streamservice.model;

import java.util.UUID;

public record StreamConsumerLease(UUID cameraId, String consumerId, String consumerType, UUID tenantId) {
    public StreamConsumerLease(UUID cameraId, String consumerId, String consumerType) {
        this(cameraId, consumerId, consumerType, null);
    }
}
