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

    private static CameraController controller(CameraService cameras, UUID tenant, UUID user, Role role) {
        TenantMembership membership = new TenantMembership(tenant, user, "tenant", "Tenant", role);
        TenantAuthorization authorization = new TenantAuthorization((principal, selected) -> Optional.of(membership),
                (principal, selected, permission) -> TenantPermissions.allows(role, permission));
        CameraController controller = new CameraController(cameras, mock(CameraMonitoringService.class), authorization);
        ReflectionTestUtils.setField(controller, "securityMode", "oidc");
        return controller;
    }

    private static Jwt jwt(UUID subject) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("isolated-test-token").header("alg", "none")
                .issuer("https://issuer.example/realm").subject(subject.toString())
                .issuedAt(now).expiresAt(now.plusSeconds(60)).build();
    }
}
