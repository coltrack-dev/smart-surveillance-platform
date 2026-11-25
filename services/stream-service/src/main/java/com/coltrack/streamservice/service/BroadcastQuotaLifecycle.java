package com.coltrack.streamservice.service;

import com.coltrack.identity.quota.TenantQuotaGuard;
import com.coltrack.streamservice.model.Broadcast;
import com.coltrack.streamservice.repository.BroadcastRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.time.Instant;
import java.util.UUID;

/** Owns atomic tenant enabled-state transitions; media side effects happen after this bean commits. */
@Service
public class BroadcastQuotaLifecycle {
    private final BroadcastRepository repository;
    private final TenantQuotaGuard quotas;

    public BroadcastQuotaLifecycle(BroadcastRepository repository, TenantQuotaGuard quotas) {
        this.repository = repository;
        this.quotas = quotas;
    }

    @Transactional
    public Transition setEnabled(UUID id, UUID tenantId, boolean enabled) {
        quotas.lock(tenantId);
        Broadcast current = repository.find(id, tenantId).orElseThrow(() -> new BroadcastService.BroadcastNotFoundException(id));
        if (enabled && !current.enabled()) quotas.requireCapacity(tenantId, TenantQuotaGuard.Resource.BROADCAST);
        if (current.enabled() != enabled) repository.setEnabled(id, tenantId, enabled, Instant.now());
        if (current.enabled() && !enabled) repository.revokeViewerSessions(id, Instant.now());
        return new Transition(current, repository.find(id, tenantId).orElseThrow(), current.enabled() != enabled);
    }

    @Transactional
    public Broadcast delete(UUID id, UUID tenantId) {
        quotas.lock(tenantId);
        Broadcast current = repository.find(id, tenantId).orElseThrow(() -> new BroadcastService.BroadcastNotFoundException(id));
        repository.revokeViewerSessions(id, Instant.now());
        repository.delete(id, tenantId);
        return current;
    }

    @Transactional
    public Broadcast setVisibility(UUID id, UUID tenantId, com.coltrack.streamservice.model.BroadcastVisibility visibility) {
        quotas.lock(tenantId);
        Broadcast current = repository.find(id, tenantId)
                .orElseThrow(() -> new BroadcastService.BroadcastNotFoundException(id));
        Broadcast updated = repository.setVisibility(id, tenantId, visibility, Instant.now());
        if (current.visibility() != visibility) repository.revokeViewerSessions(id, Instant.now());
        return updated;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TenantQuotaGuard.Usage usage(UUID tenantId) {
        return quotas.usage(tenantId, TenantQuotaGuard.Resource.BROADCAST);
    }

    public record Transition(Broadcast before, Broadcast after, boolean changed) {}
}
