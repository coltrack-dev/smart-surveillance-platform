package com.coltrack.identityservice.model;

import java.util.UUID;

public final class QuotaDtos {
    private QuotaDtos() {}

    public record Limits(int maxCameras, int maxActiveBroadcasts, int maxConcurrentViewers) {}

    public record Overrides(Integer maxCameras, Integer maxActiveBroadcasts,
                            Integer maxConcurrentViewers) {}

    public record OrganizationQuotas(UUID organizationId, String planCode, String planName,
                                     Limits limits, Overrides overrides) {}
}
