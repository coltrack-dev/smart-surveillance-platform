package com.coltrack.cameraservice.service;

import com.coltrack.cameraservice.entity.CameraEntity;
import com.coltrack.cameraservice.repository.CameraCreateIdempotencyRepository;
import com.coltrack.cameraservice.repository.CameraRepository;
import com.coltrack.cameraservice.repository.LbsLocationRepository;
import com.coltrack.identity.quota.TenantQuotaGuard;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CameraQuotaAdmissionTest {
    @Test
    void repeatedCreateWithSameKeyAndPayloadReturnsTheSameCameraWithoutSecondAdmission() {
        UUID tenant = UUID.randomUUID();
        CameraRepository cameras = mock(CameraRepository.class);
        CameraCreateIdempotencyRepository idempotency = mock(CameraCreateIdempotencyRepository.class);
        TenantQuotaGuard quotas = mock(TenantQuotaGuard.class);
        CameraCredentialsCipher cipher = new CameraCredentialsCipher(Base64.getEncoder().encodeToString(new byte[32]));
        CameraService service = new CameraService(cameras, mock(KafkaTemplate.class), mock(LbsLocationRepository.class),
                cipher, mock(RtspUrlResolver.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "quotaGuard", quotas);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "idempotencyRepository", idempotency);
        java.util.concurrent.atomic.AtomicReference<CameraEntity> saved = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> savedFingerprint = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<UUID> savedId = new java.util.concurrent.atomic.AtomicReference<>();
        when(cameras.nextCameraNumber()).thenReturn(10);
        when(cameras.save(any(CameraEntity.class))).thenAnswer(call -> {
            CameraEntity camera = call.getArgument(0); saved.set(camera); return camera;
        });
        when(cameras.findByIdAndTenantId(any(), eq(tenant))).thenAnswer(call -> Optional.of(saved.get()));
        doAnswer(call -> { savedFingerprint.set(call.getArgument(2)); savedId.set(call.getArgument(3)); return null; })
                .when(idempotency).save(eq(tenant), anyString(), anyString(), any());
        when(idempotency.find(eq(tenant), anyString())).thenAnswer(invocation -> {
            if (savedFingerprint.get() == null) return Optional.empty();
            return Optional.of(new CameraCreateIdempotencyRepository.Replay(savedFingerprint.get(), savedId.get()));
        });

        CameraEntity first = service.create("Front door", null, "rtsp://camera/live", "viewer", "secret",
                null, null, false, tenant, "logical-create-key-1");
        CameraEntity replay = service.create("Front door", null, "rtsp://camera/live", "viewer", "secret",
                null, null, false, tenant, "logical-create-key-1");

        assertSame(first, replay);
        verify(quotas, times(1)).requireCapacity(tenant, TenantQuotaGuard.Resource.CAMERA);
        verify(cameras, times(1)).save(any(CameraEntity.class));
        verify(idempotency, times(1)).save(eq(tenant), anyString(), anyString(), any());
    }

    @Test
    void atLimitCameraCreationDoesNotPersist() {
        UUID tenant = UUID.randomUUID();
        CameraRepository cameras = mock(CameraRepository.class);
        TenantQuotaGuard quotas = mock(TenantQuotaGuard.class);
        CameraCreateIdempotencyRepository idempotency = mock(CameraCreateIdempotencyRepository.class);
        CameraService service = new CameraService(cameras, mock(KafkaTemplate.class), mock(LbsLocationRepository.class),
                new CameraCredentialsCipher(Base64.getEncoder().encodeToString(new byte[32])), mock(RtspUrlResolver.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "quotaGuard", quotas);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "idempotencyRepository", idempotency);
        doThrow(new TenantQuotaGuard.QuotaExceededException(TenantQuotaGuard.Resource.CAMERA, 2, 2))
                .when(quotas).requireCapacity(tenant, TenantQuotaGuard.Resource.CAMERA);

        assertThrows(TenantQuotaGuard.QuotaExceededException.class, () -> service.create("camera", null,
                "rtsp://camera/live", null, null, null, null, false, tenant, "logical-create-key-2"));
        verify(cameras, never()).save(any(CameraEntity.class));
    }
}
