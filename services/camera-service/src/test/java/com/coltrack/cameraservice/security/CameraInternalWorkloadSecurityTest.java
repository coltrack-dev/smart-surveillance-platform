package com.coltrack.cameraservice.security;

import com.coltrack.cameraservice.controller.CameraInternalController;
import com.coltrack.cameraservice.config.CameraSecurityConfiguration;
import com.coltrack.cameraservice.dto.CameraConnectionResponse;
import com.coltrack.cameraservice.entity.VideoProcessingMode;
import com.coltrack.cameraservice.service.CameraService;
import com.coltrack.cameraservice.repository.CameraDeletionTombstoneRepository;
import com.coltrack.cameraservice.entity.CameraEntity;
import com.coltrack.identity.security.TenantWorkloadJwt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = CameraInternalController.class, properties = {
        "app.security.mode=oidc",
        "app.security.oidc.issuer-uri=https://issuer.example/realms/streaming",
        "app.security.oidc.jwk-set-uri=https://issuer.example/realms/streaming/protocol/openid-connect/certs",
        "app.security.oidc.audience=streaming-api",
        "app.security.internal.workload-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
@Import(CameraSecurityConfiguration.class)
class CameraInternalWorkloadSecurityTest {
    @Autowired MockMvc mvc;
    @MockBean CameraService cameraService;
    @MockBean CameraDeletionTombstoneRepository tombstones;
    @MockBean KafkaProperties kafkaProperties;

    @Test
    void internalConnectionRequiresSignedWorkloadAndUsesSignedTenantInsteadOfHeader() throws Exception {
        UUID cameraId = UUID.randomUUID(), signedTenant = UUID.randomUUID(), forgedHeader = UUID.randomUUID();
        when(cameraService.connection(cameraId, signedTenant)).thenReturn(
                new CameraConnectionResponse(cameraId, "rtsp://internal/source", VideoProcessingMode.AUTO));
        String token = TenantWorkloadJwt.issue(TenantWorkloadJwt.encoder(new byte[32]), signedTenant, Instant.now());

        mvc.perform(get("/internal/cameras/{id}/connection", cameraId)
                        .header("X-Tenant-ID", forgedHeader))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/cameras/{id}/connection", cameraId)
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", forgedHeader)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        verify(cameraService).connection(cameraId, signedTenant);
        verify(cameraService, never()).connection(cameraId, forgedHeader);
    }

    @Test
    void ownershipLookupRequiresDedicatedServiceScopeAndReturnsPersistedOwner() throws Exception {
        UUID cameraId = UUID.randomUUID(), tenantId = UUID.randomUUID();
        when(cameraService.findOptionalById(cameraId)).thenReturn(java.util.Optional.of(
                CameraEntity.builder().id(cameraId).tenantId(tenantId).createdAt(Instant.now()).build()));
        String tenantToken = TenantWorkloadJwt.issue(TenantWorkloadJwt.encoder(new byte[32]), tenantId, Instant.now());
        String ownerToken = TenantWorkloadJwt.issueOwnershipLookup(TenantWorkloadJwt.encoder(new byte[32]), Instant.now());

        mvc.perform(get("/internal/cameras/{id}/ownership", cameraId)
                        .header("Authorization", "Bearer " + tenantToken))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/cameras/{id}/ownership", cameraId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.tenantId").value(tenantId.toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.state").value("ACTIVE"));
    }
}
