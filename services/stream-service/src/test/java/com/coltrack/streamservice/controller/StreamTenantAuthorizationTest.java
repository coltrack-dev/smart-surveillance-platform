package com.coltrack.streamservice.controller;

import com.coltrack.identity.model.Role;
import com.coltrack.identity.model.TenantMembership;
import com.coltrack.identity.security.JdbcTenantMembershipResolver;
import com.coltrack.identity.security.TenantAuthorization;
import com.coltrack.identity.security.TenantPermissions;
import com.coltrack.streamservice.client.CameraClient;
import com.coltrack.streamservice.client.dto.CameraDto;
import com.coltrack.streamservice.model.StreamSession;
import com.coltrack.streamservice.model.StreamStatus;
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
    void missingMembershipDeniesEveryStreamRouteBeforeResourceLookup() {
        UUID tenantId = UUID.randomUUID(), userId = UUID.randomUUID(), cameraId = UUID.randomUUID();
        StreamManager manager = mock(StreamManager.class);
        CameraClient cameras = mock(CameraClient.class);
        TenantAuthorization noMembership = new TenantAuthorization(
                (principal, selected) -> Optional.empty(), (principal, selected, permission) -> false);
        StreamController controller = new StreamController(manager, cameras, noMembership);
        ReflectionTestUtils.setField(controller, "securityMode", "oidc");

        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> controller.start(cameraId, jwt(userId), tenantId)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> controller.stop(cameraId, jwt(userId), tenantId)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> controller.get(cameraId, jwt(userId), tenantId)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> controller.findAll(jwt(userId), tenantId)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> controller.cameras(jwt(userId), tenantId)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> controller.getHlsUrl(cameraId, jwt(userId), tenantId)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> controller.remove(cameraId, jwt(userId), tenantId)).getStatusCode().value());

        verifyNoInteractions(manager, cameras);
    }

    @Test
    void crossTenantStartReadUrlAndRemoveAreHiddenBeforeStreamManagerAccess() {
        UUID tenantId = UUID.randomUUID(), userId = UUID.randomUUID(), cameraId = UUID.randomUUID();
        StreamManager manager = mock(StreamManager.class);
        CameraClient cameras = mock(CameraClient.class);
        when(cameras.findById(cameraId, tenantId)).thenThrow(notFound());
        StreamController controller = controller(manager, cameras, tenantId, userId, Role.OPERATOR);

        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> controller.start(cameraId, jwt(userId), tenantId)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> controller.get(cameraId, jwt(userId), tenantId)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> controller.getHlsUrl(cameraId, jwt(userId), tenantId)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> controller.remove(cameraId, jwt(userId), tenantId)).getStatusCode().value());

        verify(cameras, times(4)).findById(cameraId, tenantId);
        verifyNoInteractions(manager);
    }

    @Test
    void unassignedLegacyCameraCannotStartReadStatusOrReleaseAStreamLease() {
        UUID tenantId = UUID.randomUUID(), userId = UUID.randomUUID(), cameraId = UUID.randomUUID();
        StreamManager manager = mock(StreamManager.class);
        CameraClient cameras = mock(CameraClient.class);
        when(cameras.findById(cameraId, tenantId)).thenThrow(notFound());
        StreamController controller = controller(manager, cameras, tenantId, userId, Role.OPERATOR);

        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> controller.start(cameraId, jwt(userId), tenantId)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> controller.get(cameraId, jwt(userId), tenantId)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> controller.getHlsUrl(cameraId, jwt(userId), tenantId)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> controller.stop(cameraId, jwt(userId), tenantId)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> controller.remove(cameraId, jwt(userId), tenantId)).getStatusCode().value());

        verify(cameras, times(5)).findById(cameraId, tenantId);
        verify(cameras, never()).findById(cameraId);
        verifyNoInteractions(manager);
    }

    @Test
    void streamListsUseTenantScopedLeaseViews() {
        UUID tenantId = UUID.randomUUID(), userId = UUID.randomUUID();
        StreamManager manager = mock(StreamManager.class);
        CameraClient cameras = mock(CameraClient.class);
        StreamController controller = controller(manager, cameras, tenantId, userId, Role.VIEWER);

        controller.findAll(jwt(userId), tenantId);
        controller.cameras(jwt(userId), tenantId);

        verify(manager).findAll(tenantId);
        verify(manager).findAvailableCameras(tenantId);
        verify(manager, never()).findAll();
        verify(manager, never()).findAvailableCameras();
    }

    @Test
    void authorizedStartAndStopUseTenantSpecificConsumerLeaseOperations() {
        UUID tenantId = UUID.randomUUID(), userId = UUID.randomUUID(), cameraId = UUID.randomUUID();
        StreamManager manager = mock(StreamManager.class);
        CameraClient cameras = mock(CameraClient.class);
        when(cameras.findById(cameraId, tenantId)).thenReturn(new CameraDto(cameraId, "owned", null, null,
                "ONLINE", Instant.now(), null, false));
        StreamSession session = StreamSession.builder().cameraId(cameraId).status(StreamStatus.RUNNING).build();
        when(manager.start(cameraId, tenantId)).thenReturn(session);
        StreamController controller = controller(manager, cameras, tenantId, userId, Role.OPERATOR);

        controller.start(cameraId, jwt(userId), tenantId);
        controller.stop(cameraId, jwt(userId), tenantId);

        verify(cameras, times(2)).findById(cameraId, tenantId);
        verify(manager).start(cameraId, tenantId);
        verify(manager).stop(cameraId, tenantId);
        verify(manager, never()).start(cameraId);
        verify(manager, never()).stop(cameraId);
    }

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

    private static HttpClientErrorException notFound() {
        return HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY,
                new byte[0], StandardCharsets.UTF_8);
    }
}
