package com.coltrack.streamservice.service;

import com.coltrack.identity.quota.TenantQuotaGuard;
import com.coltrack.streamservice.model.Broadcast;
import com.coltrack.streamservice.model.BroadcastVisibility;
import com.coltrack.streamservice.repository.BroadcastRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BroadcastQuotaLifecycleTest {
    @Test
    void activationReservesBeforePersistingEnabledState() {
        UUID tenant = UUID.randomUUID(), id = UUID.randomUUID(), camera = UUID.randomUUID();
        Instant now = Instant.now();
        Broadcast before = new Broadcast(id, camera, UUID.randomUUID(), BroadcastVisibility.PRIVATE, false, 0, now, now, tenant);
        Broadcast after = new Broadcast(id, camera, before.publicId(), BroadcastVisibility.PRIVATE, true, 1, now, now, tenant);
        BroadcastRepository repository = mock(BroadcastRepository.class);
        TenantQuotaGuard quotas = mock(TenantQuotaGuard.class);
        when(repository.find(id, tenant)).thenReturn(Optional.of(before), Optional.of(after));
        when(repository.setEnabled(eq(id), eq(tenant), eq(true), any())).thenReturn(after);

        BroadcastQuotaLifecycle.Transition transition = new BroadcastQuotaLifecycle(repository, quotas).setEnabled(id, tenant, true);

        assertFalse(transition.before().enabled());
        assertTrue(transition.after().enabled());
        assertTrue(transition.changed());
        verify(quotas).lock(tenant);
        verify(quotas).requireCapacity(tenant, TenantQuotaGuard.Resource.BROADCAST);
        verify(repository).setEnabled(eq(id), eq(tenant), eq(true), any());
    }

    @Test
    void quotaDenialLeavesBroadcastDisabled() {
        UUID tenant = UUID.randomUUID(), id = UUID.randomUUID();
        Instant now = Instant.now();
        Broadcast before = new Broadcast(id, UUID.randomUUID(), UUID.randomUUID(), BroadcastVisibility.PRIVATE, false, 0, now, now, tenant);
        BroadcastRepository repository = mock(BroadcastRepository.class);
        TenantQuotaGuard quotas = mock(TenantQuotaGuard.class);
        when(repository.find(id, tenant)).thenReturn(Optional.of(before));
        doThrow(new TenantQuotaGuard.QuotaExceededException(TenantQuotaGuard.Resource.BROADCAST, 1, 1))
                .when(quotas).requireCapacity(tenant, TenantQuotaGuard.Resource.BROADCAST);

        assertThrows(TenantQuotaGuard.QuotaExceededException.class,
                () -> new BroadcastQuotaLifecycle(repository, quotas).setEnabled(id, tenant, true));
        verify(repository, never()).setEnabled(any(), any(), anyBoolean(), any());
    }
}
