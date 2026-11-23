package com.coltrack.streamservice.controller;

import com.coltrack.identity.model.Role;
import com.coltrack.identity.model.TenantMembership;
import com.coltrack.identity.security.TenantAuthorization;
import com.coltrack.identity.security.TenantPermissions;
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

    private static Jwt jwt(UUID user) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("controller-test-token").header("alg", "none")
                .issuer("https://issuer.example/realm").subject(user.toString())
                .issuedAt(now).expiresAt(now.plusSeconds(60)).build();
    }
}
