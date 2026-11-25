package com.coltrack.streamservice.service;

import com.coltrack.identity.model.Role;
import com.coltrack.identity.model.TenantMembership;
import com.coltrack.identity.quota.TenantQuotaGuard;
import com.coltrack.identity.security.AuthenticatedPrincipal;
import com.coltrack.identity.security.JdbcTenantMembershipResolver;
import com.coltrack.streamservice.config.BroadcastProperties;
import com.coltrack.streamservice.model.Broadcast;
import com.coltrack.streamservice.model.BroadcastVisibility;
import com.coltrack.streamservice.repository.BroadcastRepository;
import com.coltrack.streamservice.repository.ViewerSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ViewerSessionServiceTest {
    private final UUID tenant = UUID.randomUUID();
    private final UUID broadcastId = UUID.randomUUID();
    private final UUID publicId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-11-25T12:00:00Z");
    private final String credential = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    private final AuthenticatedPrincipal principal = new AuthenticatedPrincipal("https://issuer.test", "subject", null, true, null);
    private BroadcastRepository broadcasts;
    private ViewerSessionRepository sessions;
    private TenantQuotaGuard quotas;
    private JdbcTenantMembershipResolver memberships;
    private PlaybackTokenService tokens;
    private BroadcastProperties properties;
    private ViewerSessionService service;

    @BeforeEach
    void setUp() {
        broadcasts = mock(BroadcastRepository.class);
        sessions = mock(ViewerSessionRepository.class);
        quotas = mock(TenantQuotaGuard.class);
        memberships = mock(JdbcTenantMembershipResolver.class);
        properties = new BroadcastProperties();
        properties.setPlaybackSecret("viewer-session-test-secret-long-enough");
        properties.setPlaybackTtl(Duration.ofMinutes(10));
        properties.setViewerSessionTtl(Duration.ofSeconds(90));
        tokens = new PlaybackTokenService(properties);
        service = new ViewerSessionService(broadcasts, sessions, quotas, memberships, tokens, properties);
        when(broadcasts.findByPublicId(publicId)).thenReturn(Optional.of(broadcast(BroadcastVisibility.PUBLIC)));
        when(sessions.findByCredentialHash(any())).thenReturn(Optional.empty());
    }

    @Test
    void twoTabCredentialsAdmitTwoIndependentSessionsAndPersistOnlyHashes() {
        String firstCredential = credential;
        String secondCredential = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[]{1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});

        var first = service.admitOrRenew(publicId, firstCredential, null, null, now);
        var second = service.admitOrRenew(publicId, secondCredential, null, null, now);

        assertNotEquals(first.sessionId(), second.sessionId());
        assertFalse(first.token().contains(firstCredential));
        verify(quotas, times(2)).reserve(tenant, TenantQuotaGuard.Resource.VIEWER);
        verify(sessions, times(2)).create(any(), eq(tenant), eq(broadcastId), isNull(), any(byte[].class), eq(now), any());
        verify(sessions, atLeastOnce()).create(any(), any(), any(), any(), argThat(hash -> hash.length == 32), any(), any());
    }

    @Test
    void activeRenewalReusesTheSessionAndDoesNotReserveAnotherSlot() {
        when(sessions.findByCredentialHash(any())).thenReturn(Optional.of(
                new ViewerSessionRepository.ViewerSession(sessionId, tenant, broadcastId, null, now.plusSeconds(20), null)));

        var renewed = service.admitOrRenew(publicId, credential, null, null, now);

        assertEquals(sessionId, renewed.sessionId());
        verify(quotas).validateViewerAuthority(tenant);
        verify(quotas, never()).reserve(any(), any());
        verify(sessions).renew(eq(sessionId), eq(now), eq(now.plusSeconds(90)));
    }

    @Test
    void expiredSessionIsReplacedWithANewIdAndConsumesCapacityAgain() {
        when(sessions.findByCredentialHash(any())).thenReturn(Optional.of(
                new ViewerSessionRepository.ViewerSession(sessionId, tenant, broadcastId, null, now, null)));

        var replacement = service.admitOrRenew(publicId, credential, null, null, now);

        assertNotEquals(sessionId, replacement.sessionId());
        verify(quotas).requireCapacity(tenant, TenantQuotaGuard.Resource.VIEWER);
        verify(sessions).renew(eq(sessionId), eq(replacement.sessionId()), eq(now), eq(now.plusSeconds(90)));
    }

    @Test
    void credentialCannotBeReusedForAnotherBroadcastOrTenant() {
        when(sessions.findByCredentialHash(any())).thenReturn(Optional.of(
                new ViewerSessionRepository.ViewerSession(sessionId, UUID.randomUUID(), broadcastId, null, now.plusSeconds(40), null)));

        assertThrows(ViewerSessionService.SessionCredentialConflictException.class,
                () -> service.admitOrRenew(publicId, credential, null, null, now));
        verify(sessions, never()).create(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void privateSessionRequiresCurrentMembershipAndBindsTheUser() {
        when(broadcasts.findByPublicId(publicId)).thenReturn(Optional.of(broadcast(BroadcastVisibility.PRIVATE)));
        when(memberships.findMembership(principal, tenant)).thenReturn(Optional.of(
                new TenantMembership(tenant, userId, "tenant", "Tenant", Role.VIEWER)));
        when(memberships.hasPermission(principal, tenant, "playback.view")).thenReturn(true);

        var admission = service.admitOrRenew(publicId, credential, principal, tenant, now);
        var grant = tokens.verify(admission.token(), now).orElseThrow();

        assertEquals(userId, grant.userId());
        assertEquals(tenant, grant.tenantId());
        verify(sessions).create(any(), eq(tenant), eq(broadcastId), eq(userId), any(), eq(now), any());
    }

    @Test
    void privateSessionDeniesMissingMembershipAndViewerQuotaFailsClosed() {
        when(broadcasts.findByPublicId(publicId)).thenReturn(Optional.of(broadcast(BroadcastVisibility.PRIVATE)));
        assertThrows(BroadcastService.PrivateBroadcastException.class,
                () -> service.admitOrRenew(publicId, credential, null, tenant, now));
        verify(sessions, never()).create(any(), any(), any(), any(), any(), any(), any());

        when(broadcasts.findByPublicId(publicId)).thenReturn(Optional.of(broadcast(BroadcastVisibility.PUBLIC)));
        doThrow(new TenantQuotaGuard.QuotaAuthorityUnavailableException(new IllegalStateException("offline")))
                .when(quotas).reserve(tenant, TenantQuotaGuard.Resource.VIEWER);
        assertThrows(TenantQuotaGuard.QuotaAuthorityUnavailableException.class,
                () -> service.admitOrRenew(publicId, credential, null, null, now));
        verify(sessions, never()).create(any(), any(), any(), any(), any(), any(), any());
    }

    private Broadcast broadcast(BroadcastVisibility visibility) {
        return new Broadcast(broadcastId, UUID.randomUUID(), publicId, visibility, true, 7, now, now, tenant);
    }
}
