package com.coltrack.streamservice.controller;

import com.coltrack.identity.model.Role;
import com.coltrack.identity.model.TenantMembership;
import com.coltrack.identity.security.TenantAuthorization;
import com.coltrack.identity.security.TenantPermissions;
import com.coltrack.identity.quota.TenantQuotaGuard;
import com.coltrack.streamservice.config.BroadcastProperties;
import com.coltrack.streamservice.security.BroadcastRateLimiter;
import com.coltrack.streamservice.service.BroadcastService;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BroadcastControllerTenantAuthorizationTest {
    @Test
    void broadcastUsageIsMembershipScopedAndNotCacheable() {
        UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
        BroadcastService service = mock(BroadcastService.class);
        when(service.usage(tenant)).thenReturn(new TenantQuotaGuard.Usage(tenant,
                TenantQuotaGuard.Resource.BROADCAST, 1, 1, 0));
        BroadcastController controller = controller(service, tenant, user, Role.VIEWER);

        var response = controller.usage(null, tenant, jwt(user));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        verify(service).usage(tenant);
    }

    @Test
    void viewerUsageIsMembershipScopedAndNotCacheable() {
        UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
        BroadcastService service = mock(BroadcastService.class);
        when(service.viewerUsage(tenant)).thenReturn(new TenantQuotaGuard.Usage(tenant,
                TenantQuotaGuard.Resource.VIEWER, 2, 4, 2));
        BroadcastController controller = controller(service, tenant, user, Role.VIEWER);

        var response = controller.viewerUsage(null, tenant, jwt(user));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        verify(service).viewerUsage(tenant);
    }

    @Test
    void missingMembershipDeniesBroadcastReadDeleteAndPlaybackInfoBeforeServiceCalls() {
        UUID tenant = UUID.randomUUID(), user = UUID.randomUUID(), id = UUID.randomUUID();
        BroadcastService service = mock(BroadcastService.class);
        TenantAuthorization noMembership = new TenantAuthorization(
                (principal, selected) -> Optional.empty(), (principal, selected, permission) -> false);
        BroadcastController controller = controller(service, noMembership);
        ReflectionTestUtils.setField(controller, "securityMode", "oidc");

        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> controller.list(null, tenant, jwt(user))).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> controller.get(null, tenant, jwt(user), id)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> controller.delete(null, tenant, jwt(user), id)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> controller.playbackInfo(null, tenant, jwt(user), id)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> controller.usage(null, tenant, jwt(user))).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> controller.viewerUsage(null, tenant, jwt(user))).getStatusCode().value());

        verifyNoInteractions(service);
    }

    @Test
    void crossTenantBroadcastReadsDeleteAndPlaybackInfoUseScopedServiceMethods() {
        UUID tenant = UUID.randomUUID(), user = UUID.randomUUID(), id = UUID.randomUUID();
        BroadcastService service = mock(BroadcastService.class);
        when(service.get(id, tenant)).thenThrow(new BroadcastService.BroadcastNotFoundException(id));
        when(service.list(tenant)).thenReturn(java.util.List.of());
        when(service.playbackInfo(id, tenant)).thenThrow(new BroadcastService.BroadcastNotFoundException(id));
        doThrow(new BroadcastService.BroadcastNotFoundException(id)).when(service).delete(id, tenant);
        BroadcastController controller = controller(service, tenant, user, Role.OPERATOR);

        assertThrows(BroadcastService.BroadcastNotFoundException.class,
                () -> controller.get(null, tenant, jwt(user), id));
        assertThrows(BroadcastService.BroadcastNotFoundException.class,
                () -> controller.playbackInfo(null, tenant, jwt(user), id));
        assertThrows(BroadcastService.BroadcastNotFoundException.class,
                () -> controller.delete(null, tenant, jwt(user), id));
        assertEquals(java.util.List.of(), controller.list(null, tenant, jwt(user)));

        verify(service).get(id, tenant);
        verify(service).list(tenant);
        verify(service).playbackInfo(id, tenant);
        verify(service).delete(id, tenant);
        verify(service, never()).get(id);
        verify(service, never()).list();
        verify(service, never()).playbackInfo(id);
        verify(service, never()).delete(id);
    }

    @Test
    void unassignedLegacyBroadcastIsHiddenFromTenantListReadDeleteAndPlaybackInfo() {
        UUID tenant = UUID.randomUUID(), user = UUID.randomUUID(), id = UUID.randomUUID();
        BroadcastService service = mock(BroadcastService.class);
        when(service.list(tenant)).thenReturn(java.util.List.of());
        when(service.get(id, tenant)).thenThrow(new BroadcastService.BroadcastNotFoundException(id));
        when(service.playbackInfo(id, tenant)).thenThrow(new BroadcastService.BroadcastNotFoundException(id));
        doThrow(new BroadcastService.BroadcastNotFoundException(id)).when(service).delete(id, tenant);
        BroadcastController controller = controller(service, tenant, user, Role.OPERATOR);

        assertEquals(java.util.List.of(), controller.list(null, tenant, jwt(user)));
        assertThrows(BroadcastService.BroadcastNotFoundException.class,
                () -> controller.get(null, tenant, jwt(user), id));
        assertThrows(BroadcastService.BroadcastNotFoundException.class,
                () -> controller.delete(null, tenant, jwt(user), id));
        assertThrows(BroadcastService.BroadcastNotFoundException.class,
                () -> controller.playbackInfo(null, tenant, jwt(user), id));

        verify(service).list(tenant);
        verify(service).get(id, tenant);
        verify(service).delete(id, tenant);
        verify(service).playbackInfo(id, tenant);
        verify(service, never()).list();
        verify(service, never()).get(id);
        verify(service, never()).delete(id);
        verify(service, never()).playbackInfo(id);
    }

    @Test
    void viewerCannotEnableOrCreateBroadcastAndCannotAcquireBroadcastLease() {
        UUID tenant = UUID.randomUUID(), user = UUID.randomUUID(), id = UUID.randomUUID();
        BroadcastService service = mock(BroadcastService.class);
        BroadcastController controller = controller(service, tenant, user, Role.VIEWER);

        ResponseStatusException denied = assertThrows(ResponseStatusException.class,
                () -> controller.enabled(null, tenant, jwt(user), id, new BroadcastController.EnabledRequest(true)));

        assertEquals(403, denied.getStatusCode().value());
        verifyNoInteractions(service);
    }

    @Test
    void managementMutationUsesSelectedValidatedTenantForObjectLookup() {
        UUID tenantA = UUID.randomUUID(), user = UUID.randomUUID(), broadcastFromTenantB = UUID.randomUUID();
        BroadcastService service = mock(BroadcastService.class);
        BroadcastController controller = controller(service, tenantA, user, Role.OPERATOR);

        controller.enabled(null, tenantA, jwt(user), broadcastFromTenantB,
                new BroadcastController.EnabledRequest(false));

        verify(service).setEnabled(broadcastFromTenantB, tenantA, false);
        verify(service, never()).setEnabled(broadcastFromTenantB, false);
    }

    private static BroadcastController controller(BroadcastService service, UUID tenant, UUID user, Role role) {
        TenantMembership membership = new TenantMembership(tenant, user, "tenant", "Tenant", role);
        TenantAuthorization authorization = new TenantAuthorization((principal, selected) -> Optional.of(membership),
                (principal, selected, permission) -> TenantPermissions.allows(role, permission));
        BroadcastController controller = new BroadcastController(service, new BroadcastProperties(),
                new BroadcastRateLimiter(), authorization);
        ReflectionTestUtils.setField(controller, "securityMode", "oidc");
        return controller;
    }

    private static BroadcastController controller(BroadcastService service, TenantAuthorization authorization) {
        BroadcastController controller = new BroadcastController(service, new BroadcastProperties(),
                new BroadcastRateLimiter(), authorization);
        ReflectionTestUtils.setField(controller, "securityMode", "oidc");
        return controller;
    }

    private static Jwt jwt(UUID user) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("controller-test-token").header("alg", "none")
                .issuer("https://issuer.example/realm").subject(user.toString())
                .issuedAt(now).expiresAt(now.plusSeconds(60)).build();
    }
}
