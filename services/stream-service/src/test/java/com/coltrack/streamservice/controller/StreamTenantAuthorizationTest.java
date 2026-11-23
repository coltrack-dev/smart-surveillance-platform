package com.coltrack.streamservice.controller;

import com.coltrack.identity.model.Role;
import com.coltrack.identity.model.TenantMembership;
import com.coltrack.identity.security.JdbcTenantMembershipResolver;
import com.coltrack.identity.security.TenantAuthorization;
import com.coltrack.identity.security.TenantPermissions;
import com.coltrack.streamservice.client.CameraClient;
import com.coltrack.streamservice.service.StreamManager;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StreamTenantAuthorizationTest {
    @Test
    void viewerCannotStopCameraStream() {
        UUID tenantId = UUID.randomUUID(), userId = UUID.randomUUID(), cameraId = UUID.randomUUID();
        StreamManager manager = mock(StreamManager.class);
        CameraClient cameras = mock(CameraClient.class);
        StreamController controller = controller(manager, cameras, tenantId, userId, Role.VIEWER);

        ResponseStatusException denied = assertThrows(ResponseStatusException.class,
                () -> controller.stop(cameraId, jwt(userId), tenantId));

        assertEquals(403, denied.getStatusCode().value());
        verifyNoInteractions(cameras, manager);
    }

    @Test
    void tenantMemberCannotStopAnotherTenantsCamera() {
        UUID tenantId = UUID.randomUUID(), userId = UUID.randomUUID(), cameraId = UUID.randomUUID();
        StreamManager manager = mock(StreamManager.class);
        CameraClient cameras = mock(CameraClient.class);
        when(cameras.findById(cameraId, tenantId)).thenThrow(HttpClientErrorException.create(
                HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));
        StreamController controller = controller(manager, cameras, tenantId, userId, Role.OPERATOR);

        ResponseStatusException hidden = assertThrows(ResponseStatusException.class,
                () -> controller.stop(cameraId, jwt(userId), tenantId));

        assertEquals(404, hidden.getStatusCode().value());
        verify(cameras).findById(cameraId, tenantId);
        verifyNoInteractions(manager);
    }

    private static StreamController controller(StreamManager manager, CameraClient cameras,
                                               UUID tenantId, UUID userId, Role role) {
        JdbcTenantMembershipResolver memberships = mock(JdbcTenantMembershipResolver.class);
        when(memberships.findMembership(any(), eq(tenantId))).thenReturn(Optional.of(
                new TenantMembership(tenantId, userId, "tenant", "Tenant", role)));
        TenantAuthorization authorization = new TenantAuthorization(memberships,
                (principal, tenant, permission) -> TenantPermissions.allows(role, permission));
        StreamController controller = new StreamController(manager, cameras, authorization);
        ReflectionTestUtils.setField(controller, "securityMode", "oidc");
        return controller;
    }

    private static Jwt jwt(UUID userId) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("unit-test-token").header("alg", "none")
                .issuer("https://issuer.example/realm").subject(userId.toString())
                .issuedAt(now).expiresAt(now.plusSeconds(60)).build();
    }
}
