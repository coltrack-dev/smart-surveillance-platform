package com.coltrack.streamservice.model;

import java.util.UUID;

public record StreamConsumerLease(UUID cameraId, String consumerId, String consumerType) {}
