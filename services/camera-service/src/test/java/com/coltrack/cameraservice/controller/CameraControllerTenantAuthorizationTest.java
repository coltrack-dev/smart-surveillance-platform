package com.coltrack.cameraservice.controller;

import com.coltrack.cameraservice.dto.CreateCameraRequest;
import com.coltrack.cameraservice.entity.CameraEntity;
import com.coltrack.cameraservice.entity.CameraStatus;
import com.coltrack.cameraservice.service.CameraMonitoringService;
import com.coltrack.cameraservice.service.CameraService;
import com.coltrack.identity.model.Role;
import com.coltrack.identity.model.TenantMembership;
import com.coltrack.identity.security.TenantAuthorization;
import com.coltrack.identity.security.TenantPermissions;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CameraControllerTenantAuthorizationTest {
    @Test
    void tenantCameraListUsesMembershipScopedRepositoryPath() {
        UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
        CameraService cameras = mock(CameraService.class);
        var pageable = PageRequest.of(0, 20);
        when(cameras.findAll(tenant, pageable)).thenReturn(Page.empty(pageable));
        CameraController controller = controller(cameras, tenant, user, Role.VIEWER);

        controller.findAll(pageable, jwt(user), tenant);

        verify(cameras).findAll(tenant, pageable);
        verify(cameras, never()).findAll(pageable);
    }

    @Test
    void absentMembershipDeniesListAndCameraMutationsBeforeAnyLookup() {
        UUID tenant = UUID.randomUUID(), user = UUID.randomUUID(), cameraId = UUID.randomUUID();
        CameraService cameras = mock(CameraService.class);
        CameraMonitoringService monitoring = mock(CameraMonitoringService.class);
        TenantAuthorization noMembership = new TenantAuthorization(
                (principal, selected) -> Optional.empty(), (principal, selected, permission) -> false);
        CameraController controller = new CameraController(cameras, monitoring, noMembership);
        ReflectionTestUtils.setField(controller, "securityMode", "oidc");
        CreateCameraRequest request = new CreateCameraRequest("camera", null, null, "rtsp://nvr/live",
                null, null, null, null, false);

        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> controller.findAll(PageRequest.of(0, 10), jwt(user), tenant)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> controller.update(cameraId, request, jwt(user), tenant)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> controller.delete(cameraId, jwt(user), tenant)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> controller.heartbeat(cameraId, jwt(user), tenant)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> controller.checkStream(cameraId, jwt(user), tenant)).getStatusCode().value());

        verifyNoInteractions(cameras, monitoring);
    }

    @Test
    void crossTenantCameraMutationsUseScopedLookupsAndReturnNotFound() {
        UUID tenant = UUID.randomUUID(), user = UUID.randomUUID(), cameraId = UUID.randomUUID();
        CameraService cameras = mock(CameraService.class);
        CameraMonitoringService monitoring = mock(CameraMonitoringService.class);
        doThrow(notFound()).when(cameras).update(eq(cameraId), any(), any(), any(), any(), any(), any(), any(), eq(tenant));
        doThrow(notFound()).when(cameras).delete(cameraId, tenant);
        doThrow(notFound()).when(cameras).heartbeat(cameraId, tenant);
        when(monitoring.checkCamera(cameraId, tenant)).thenThrow(notFound());
        CameraController controller = new CameraController(cameras, monitoring,
                authorization(tenant, user, Role.OPERATOR));
        ReflectionTestUtils.setField(controller, "securityMode", "oidc");
        CreateCameraRequest request = new CreateCameraRequest("camera", null, null, "rtsp://nvr/live",
                null, null, null, null, false);

        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> controller.update(cameraId, request, jwt(user), tenant)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> controller.delete(cameraId, jwt(user), tenant)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> controller.heartbeat(cameraId, jwt(user), tenant)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> controller.checkStream(cameraId, jwt(user), tenant)).getStatusCode().value());

        verify(cameras).update(eq(cameraId), any(), any(), any(), any(), any(), any(), any(), eq(tenant));
        verify(cameras).delete(cameraId, tenant);
        verify(cameras).heartbeat(cameraId, tenant);
        verify(monitoring).checkCamera(cameraId, tenant);
        verify(cameras, never()).update(eq(cameraId), any(), any(), any(), any(), any(), any(), any());
        verify(cameras, never()).delete(cameraId);
        verify(cameras, never()).heartbeat(cameraId);
        verify(monitoring, never()).checkCamera(cameraId);
    }

    @Test
    void createAssignsTenantFromMembershipContext() {
        UUID tenant = UUID.randomUUID(), user = UUID.randomUUID(), cameraId = UUID.randomUUID();
        CameraService cameras = mock(CameraService.class);
        when(cameras.create(eq("camera"), isNull(), eq("rtsp://nvr/live"), isNull(), isNull(),
                isNull(), isNull(), eq(false), eq(tenant))).thenReturn(CameraEntity.builder()
                .id(cameraId).name("camera").status(CameraStatus.OFFLINE).createdAt(Instant.now()).build());
        CameraController controller = controller(cameras, tenant, user, Role.OPERATOR);

        controller.create(new CreateCameraRequest("camera", null, null, "rtsp://nvr/live", null, null,
                null, null, false), jwt(user), tenant);

        verify(cameras).create(eq("camera"), isNull(), eq("rtsp://nvr/live"), isNull(), isNull(),
                isNull(), isNull(), eq(false), eq(tenant));
    }

    @Test
    void missingMembershipDoesNotFallBackToUnscopedCameraLookup() {
        UUID tenant = UUID.randomUUID(), user = UUID.randomUUID(), cameraId = UUID.randomUUID();
        CameraService cameras = mock(CameraService.class);
        CameraController controller = new CameraController(cameras, mock(CameraMonitoringService.class),
                new TenantAuthorization((principal, selected) -> Optional.empty(), (principal, selected, permission) -> false));
        ReflectionTestUtils.setField(controller, "securityMode", "oidc");

        ResponseStatusException denied = assertThrows(ResponseStatusException.class,
                () -> controller.findById(cameraId, jwt(user), tenant));

        assertEquals(404, denied.getStatusCode().value());
        verify(cameras, never()).findById(cameraId);
        verify(cameras, never()).findById(eq(cameraId), any());
    }

    @Test
    void crossTenantCameraGetIsHiddenWithoutUnscopedFallback() {
        UUID tenant = UUID.randomUUID(), user = UUID.randomUUID(), cameraId = UUID.randomUUID();
        CameraService cameras = mock(CameraService.class);
        when(cameras.findById(cameraId, tenant))
                .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        CameraController controller = controller(cameras, tenant, user, Role.VIEWER);

        ResponseStatusException hidden = assertThrows(ResponseStatusException.class,
                () -> controller.findById(cameraId, jwt(user), tenant));

        assertEquals(404, hidden.getStatusCode().value());
        verify(cameras).findById(cameraId, tenant);
        verify(cameras, never()).findById(cameraId);
    }

    @Test
    void unassignedLegacyCameraIsHiddenByTenantScopedReadAndMutationRoutes() {
        UUID tenant = UUID.randomUUID(), user = UUID.randomUUID(), cameraId = UUID.randomUUID();
        CameraService cameras = mock(CameraService.class);
        CameraMonitoringService monitoring = mock(CameraMonitoringService.class);
        when(cameras.findById(cameraId, tenant))
                .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND))
                .when(cameras).update(eq(cameraId), any(), any(), any(), any(), any(), any(), any(), eq(tenant));
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND)).when(cameras).delete(cameraId, tenant);
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND)).when(cameras).heartbeat(cameraId, tenant);
        when(monitoring.checkCamera(cameraId, tenant))
                .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        CameraController controller = new CameraController(cameras, monitoring,
                authorization(tenant, user, Role.OPERATOR));
        ReflectionTestUtils.setField(controller, "securityMode", "oidc");
        CreateCameraRequest request = new CreateCameraRequest("camera", null, null, "rtsp://nvr/live",
                null, null, null, null, false);

        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> controller.findById(cameraId, jwt(user), tenant)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> controller.update(cameraId, request, jwt(user), tenant)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> controller.delete(cameraId, jwt(user), tenant)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> controller.heartbeat(cameraId, jwt(user), tenant)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> controller.checkStream(cameraId, jwt(user), tenant)).getStatusCode().value());

        verify(cameras).findById(cameraId, tenant);
        verify(cameras, never()).findById(cameraId);
        verify(cameras, never()).update(eq(cameraId), any(), any(), any(), any(), any(), any(), any());
        verify(cameras, never()).delete(cameraId);
        verify(cameras, never()).heartbeat(cameraId);
        verify(monitoring, never()).checkCamera(cameraId);
    }

    private static CameraController controller(CameraService cameras, UUID tenant, UUID user, Role role) {
        CameraController controller = new CameraController(cameras, mock(CameraMonitoringService.class),
                authorization(tenant, user, role));
        ReflectionTestUtils.setField(controller, "securityMode", "oidc");
        return controller;
    }

    private static TenantAuthorization authorization(UUID tenant, UUID user, Role role) {
        TenantMembership membership = new TenantMembership(tenant, user, "tenant", "Tenant", role);
        return new TenantAuthorization((principal, selected) -> Optional.of(membership),
                (principal, selected, permission) -> TenantPermissions.allows(role, permission));
    }

    private static Jwt jwt(UUID subject) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("isolated-test-token").header("alg", "none")
                .issuer("https://issuer.example/realm").subject(subject.toString())
                .issuedAt(now).expiresAt(now.plusSeconds(60)).build();
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
    }
}
