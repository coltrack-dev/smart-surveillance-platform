package com.coltrack.cameraservice.service;

import com.coltrack.cameraservice.entity.CameraEntity;
import com.coltrack.cameraservice.repository.CameraRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import com.coltrack.cameraservice.entity.RtspUrlFormat;
import com.coltrack.cameraservice.entity.VideoProcessingMode;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CameraTenantOwnershipTest {
    @Test
    void tenantLookupHidesUnknownAndOtherTenantCameras() {
        CameraRepository repository = mock(CameraRepository.class);
        CameraService service = new CameraService(repository, null, null, null, null);
        UUID cameraId = UUID.randomUUID(), tenantId = UUID.randomUUID();
        when(repository.findByIdAndTenantId(cameraId, tenantId)).thenReturn(Optional.empty());

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.findById(cameraId, tenantId));

        assertEquals(404, error.getStatusCode().value());
        verify(repository, never()).findById(cameraId);
    }

    @Test
    void legacyLookupRemainsUnscopedForSurveillance() {
        CameraRepository repository = mock(CameraRepository.class);
        CameraService service = new CameraService(repository, null, null, null, null);
        UUID cameraId = UUID.randomUUID();
        CameraEntity camera = CameraEntity.builder().id(cameraId).build();
        when(repository.findById(cameraId)).thenReturn(Optional.of(camera));

        assertSame(camera, service.findById(cameraId));
        verify(repository).findById(cameraId);
        verify(repository, never()).findByIdAndTenantId(any(), any());
    }

    @Test
    void streamingCreateTakesOwnerOnlyFromVerifiedServiceArgument() {
        CameraRepository repository = mock(CameraRepository.class);
        CameraCredentialsCipher cipher = mock(CameraCredentialsCipher.class);
        @SuppressWarnings("unchecked") KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        CameraService service = new CameraService(repository, kafka, null, cipher, null);
        UUID tenantId = UUID.randomUUID();
        when(repository.nextCameraNumber()).thenReturn(71);
        when(cipher.encrypt("secret")).thenReturn("encrypted");

        service.create("camera", null, "rtsp://nvr/live", "user", "secret", RtspUrlFormat.STANDARD,
                VideoProcessingMode.AUTO, false, tenantId);

        ArgumentCaptor<CameraEntity> stored = ArgumentCaptor.forClass(CameraEntity.class);
        verify(repository).save(stored.capture());
        assertEquals(tenantId, stored.getValue().getTenantId());
        assertEquals("encrypted", stored.getValue().getRtspPasswordEncrypted());
    }
}
