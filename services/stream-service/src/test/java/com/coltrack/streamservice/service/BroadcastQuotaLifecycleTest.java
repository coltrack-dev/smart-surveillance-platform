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

    @Test
    void disablingBroadcastRevokesItsViewerSessionsInTheLifecycleTransaction() {
        UUID tenant = UUID.randomUUID(), id = UUID.randomUUID();
        Instant now = Instant.now();
        Broadcast before = new Broadcast(id, UUID.randomUUID(), UUID.randomUUID(), BroadcastVisibility.PUBLIC,
                true, 1, now, now, tenant);
        Broadcast after = new Broadcast(id, before.cameraId(), before.publicId(), before.visibility(),
                false, 2, now, now, tenant);
        BroadcastRepository repository = mock(BroadcastRepository.class);
        TenantQuotaGuard quotas = mock(TenantQuotaGuard.class);
        when(repository.find(id, tenant)).thenReturn(Optional.of(before), Optional.of(after));
        when(repository.setEnabled(eq(id), eq(tenant), eq(false), any())).thenReturn(after);

        new BroadcastQuotaLifecycle(repository, quotas).setEnabled(id, tenant, false);

        verify(repository).revokeViewerSessions(eq(id), any());
    }

    @Test
    void visibilityChangeRevokesExistingViewerSessions() {
        UUID tenant = UUID.randomUUID(), id = UUID.randomUUID();
        Instant now = Instant.now();
        Broadcast before = new Broadcast(id, UUID.randomUUID(), UUID.randomUUID(), BroadcastVisibility.PUBLIC,
                true, 1, now, now, tenant);
        Broadcast after = new Broadcast(id, before.cameraId(), before.publicId(), BroadcastVisibility.PRIVATE,
                true, 2, now, now, tenant);
        BroadcastRepository repository = mock(BroadcastRepository.class);
        TenantQuotaGuard quotas = mock(TenantQuotaGuard.class);
        when(repository.find(id, tenant)).thenReturn(Optional.of(before));
        when(repository.setVisibility(eq(id), eq(tenant), eq(BroadcastVisibility.PRIVATE), any())).thenReturn(after);

        new BroadcastQuotaLifecycle(repository, quotas).setVisibility(id, tenant, BroadcastVisibility.PRIVATE);

        verify(repository).revokeViewerSessions(eq(id), any());
    }

    @Test
    void deletingBroadcastRevokesViewerSessionsBeforeDelete() {
        UUID tenant = UUID.randomUUID(), id = UUID.randomUUID();
        Instant now = Instant.now();
        Broadcast current = new Broadcast(id, UUID.randomUUID(), UUID.randomUUID(), BroadcastVisibility.PUBLIC,
                true, 1, now, now, tenant);
        BroadcastRepository repository = mock(BroadcastRepository.class);
        TenantQuotaGuard quotas = mock(TenantQuotaGuard.class);
        when(repository.find(id, tenant)).thenReturn(Optional.of(current));

        new BroadcastQuotaLifecycle(repository, quotas).delete(id, tenant);

        var order = inOrder(repository);
        order.verify(repository).revokeViewerSessions(eq(id), any());
        order.verify(repository).delete(id, tenant);
    }
}
