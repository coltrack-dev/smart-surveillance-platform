package com.coltrack.streamservice.service;

import com.coltrack.identity.model.Role;
import com.coltrack.identity.security.JdbcTenantMembershipResolver;
import com.coltrack.identity.security.TenantContext;
import com.coltrack.streamservice.client.CameraClient;
import com.coltrack.streamservice.client.dto.CameraConnectionDto;
import com.coltrack.streamservice.config.BroadcastProperties;
import com.coltrack.streamservice.model.Broadcast;
import com.coltrack.streamservice.model.BroadcastVisibility;
import com.coltrack.streamservice.repository.BroadcastRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BroadcastTenantOwnershipTest {
    @Test
    void creatingBroadcastChecksCameraThroughTenantDelegationAndPersistsOwner() {
        BroadcastRepository repository = mock(BroadcastRepository.class);
        CameraClient cameras = mock(CameraClient.class);
        UUID cameraId = UUID.randomUUID(), tenantId = UUID.randomUUID(), broadcastId = UUID.randomUUID();
        when(repository.findByCamera(cameraId, tenantId)).thenReturn(Optional.empty());
        when(cameras.connection(cameraId, tenantId)).thenReturn(new CameraConnectionDto(cameraId,
                "rtsp://private/source", com.coltrack.streamservice.model.VideoProcessingMode.AUTO));
        when(repository.create(any(), eq(cameraId), any(), eq(BroadcastVisibility.PRIVATE), eq(tenantId), any()))
                .thenReturn(new Broadcast(broadcastId, cameraId, UUID.randomUUID(), BroadcastVisibility.PRIVATE,
                        false, 0, Instant.now(), Instant.now(), tenantId));
        BroadcastService service = service(repository, cameras, mock(JdbcTenantMembershipResolver.class));

        Broadcast created = service.create(cameraId, tenantId, BroadcastVisibility.PRIVATE);

        assertEquals(tenantId, created.tenantId());
        verify(cameras).connection(cameraId, tenantId);
        verify(repository).create(any(), eq(cameraId), any(), eq(BroadcastVisibility.PRIVATE), eq(tenantId), any());
        verify(cameras, never()).connection(cameraId);
    }

    @Test
    void privatePlaybackIsRevokedWhenMembershipIsRevoked() {
        BroadcastRepository repository = mock(BroadcastRepository.class);
        JdbcTenantMembershipResolver memberships = mock(JdbcTenantMembershipResolver.class);
        UUID id = UUID.randomUUID(), cameraId = UUID.randomUUID(), publicId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID(), userId = UUID.randomUUID();
        Broadcast privateBroadcast = new Broadcast(id, cameraId, publicId, BroadcastVisibility.PRIVATE,
                true, 5, Instant.now(), Instant.now(), tenantId);
        when(repository.findByPublicId(publicId)).thenReturn(Optional.of(privateBroadcast));
        when(memberships.hasPermission(userId, tenantId, "playback.view")).thenReturn(true, false);
        BroadcastService service = service(repository, mock(CameraClient.class), memberships);
        TenantContext viewer = new TenantContext(tenantId, userId, Role.VIEWER);

        BroadcastService.PlaybackToken grant = service.playbackToken(publicId, viewer);
        assertThrows(BroadcastService.PrivateBroadcastException.class,
                () -> service.authorizePlayback(publicId, grant.token(), Instant.now()));
        verify(memberships, times(2)).hasPermission(userId, tenantId, "playback.view");
    }

    @Test
    void crossTenantBroadcastLookupUsesTenantQualifiedQueryAndReturnsNotFound() {
        BroadcastRepository repository = mock(BroadcastRepository.class);
        UUID id = UUID.randomUUID(), tenantId = UUID.randomUUID();
        when(repository.find(id, tenantId)).thenReturn(Optional.empty());
        BroadcastService service = service(repository, mock(CameraClient.class), mock(JdbcTenantMembershipResolver.class));

        assertThrows(BroadcastService.BroadcastNotFoundException.class, () -> service.get(id, tenantId));
        verify(repository).find(id, tenantId);
        verify(repository, never()).find(id);
    }

    @Test
    void creationDoesNotClaimUnassignedLegacyBroadcast() {
        BroadcastRepository repository = mock(BroadcastRepository.class);
        CameraClient cameras = mock(CameraClient.class);
        UUID cameraId = UUID.randomUUID(), tenantId = UUID.randomUUID();
        Broadcast legacy = new Broadcast(UUID.randomUUID(), cameraId, UUID.randomUUID(), BroadcastVisibility.PRIVATE,
                false, 0, Instant.now(), Instant.now());
        when(repository.findByCamera(cameraId, tenantId)).thenReturn(Optional.empty());
        when(repository.findByCamera(cameraId)).thenReturn(Optional.of(legacy));
        BroadcastService service = service(repository, cameras, mock(JdbcTenantMembershipResolver.class));

        assertThrows(BroadcastService.BroadcastNotFoundException.class,
                () -> service.create(cameraId, tenantId, BroadcastVisibility.PUBLIC));

        verify(repository, never()).create(any(), any(), any(), any(), any(UUID.class), any());
        verifyNoInteractions(cameras);
    }

    @Test
    void unassignedPublicBroadcastIsNotPlayableInStreamingMode() {
        BroadcastRepository repository = mock(BroadcastRepository.class);
        UUID publicId = UUID.randomUUID();
        Broadcast legacy = new Broadcast(UUID.randomUUID(), UUID.randomUUID(), publicId, BroadcastVisibility.PUBLIC,
                true, 0, Instant.now(), Instant.now());
        when(repository.findByPublicId(publicId)).thenReturn(Optional.of(legacy));
        BroadcastService service = service(repository, mock(CameraClient.class), mock(JdbcTenantMembershipResolver.class));

        assertThrows(BroadcastService.BroadcastNotFoundException.class, () -> service.playbackToken(publicId));
    }

    @Test
    void privatePlaybackGrantFromAnotherTenantIsRejected() {
        BroadcastRepository repository = mock(BroadcastRepository.class);
        JdbcTenantMembershipResolver memberships = mock(JdbcTenantMembershipResolver.class);
        UUID broadcastId = UUID.randomUUID(), cameraId = UUID.randomUUID(), publicId = UUID.randomUUID();
        UUID tenantA = UUID.randomUUID(), tenantB = UUID.randomUUID(), userA = UUID.randomUUID();
        Broadcast privateBroadcast = new Broadcast(broadcastId, cameraId, publicId, BroadcastVisibility.PRIVATE,
                true, 2, Instant.now(), Instant.now(), tenantB);
        when(repository.findByPublicId(publicId)).thenReturn(Optional.of(privateBroadcast));
        BroadcastProperties properties = new BroadcastProperties();
        properties.setPlaybackSecret("a sufficiently long isolated test playback signing secret");
        PlaybackTokenService tokens = new PlaybackTokenService(properties);
        BroadcastService service = new BroadcastService(repository, mock(CameraClient.class), mock(StreamManager.class),
                tokens, properties, memberships);
        String tenantAGrant = tokens.issue(broadcastId, 2, Instant.now().plusSeconds(60), tenantA, userA);

        assertThrows(BroadcastService.PrivateBroadcastException.class,
                () -> service.authorizePlayback(publicId, tenantAGrant, Instant.now()));
        verifyNoInteractions(memberships);
    }

    private static BroadcastService service(BroadcastRepository repository, CameraClient cameras,
                                            JdbcTenantMembershipResolver memberships) {
        BroadcastProperties properties = new BroadcastProperties();
        properties.setPlaybackSecret("a sufficiently long isolated test playback signing secret");
        return new BroadcastService(repository, cameras, mock(StreamManager.class),
                new PlaybackTokenService(properties), properties, memberships);
    }
}
