package com.coltrack.streamservice.service;

import com.coltrack.streamservice.client.CameraClient;
import com.coltrack.streamservice.config.BroadcastProperties;
import com.coltrack.streamservice.model.Broadcast;
import com.coltrack.streamservice.model.BroadcastVisibility;
import com.coltrack.streamservice.model.StreamSession;
import com.coltrack.streamservice.repository.BroadcastRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.coltrack.streamservice.model.StreamStatus;
import static org.junit.jupiter.api.Assertions.assertFalse;

import static org.mockito.Mockito.*;

class BroadcastServiceTest {
    @Test
    void streamSessionJsonDoesNotExposeSourceRtspCredentials() throws Exception {
        StreamSession session = StreamSession.builder()
                .cameraId(UUID.randomUUID())
                .rtspUrl("rtsp://camera-user:camera-password@camera.local/live")
                .status(StreamStatus.RUNNING)
                .build();

        String json = new ObjectMapper().writeValueAsString(session);

        assertFalse(json.contains("camera-user"));
        assertFalse(json.contains("camera-password"));
        assertFalse(json.contains("rtspUrl"));
    }

    @Test
    void enablingBroadcastAcquiresIndependentLeaseForExistingStream() {
        BroadcastRepository repository = mock(BroadcastRepository.class);
        CameraClient cameraClient = mock(CameraClient.class);
        StreamManager streamManager = mock(StreamManager.class);
        PlaybackTokenService tokenService = mock(PlaybackTokenService.class);
        BroadcastProperties properties = new BroadcastProperties();
        UUID broadcastId = UUID.randomUUID();
        UUID cameraId = UUID.randomUUID();
        Instant now = Instant.now();
        Broadcast broadcast = broadcast(broadcastId, cameraId, false, now);
        when(repository.find(broadcastId)).thenReturn(Optional.of(broadcast));
        when(repository.setEnabled(eq(broadcastId), eq(true), any())).thenReturn(
                new Broadcast(broadcastId, cameraId, UUID.randomUUID(), BroadcastVisibility.PRIVATE, true, 1, now, now));
        when(repository.find(broadcastId)).thenReturn(Optional.of(broadcast),
                Optional.of(new Broadcast(broadcastId, cameraId, UUID.randomUUID(), BroadcastVisibility.PRIVATE, true, 1, now, now)));

        new BroadcastService(repository, cameraClient, streamManager, tokenService, properties)
                .setEnabled(broadcastId, true);

        verify(streamManager).acquire(cameraId, "broadcast:" + broadcastId, "BROADCAST");
        verify(streamManager, never()).start(cameraId);
        verify(repository).setEnabled(eq(broadcastId), eq(true), any());
    }

    @Test
    void privateBroadcastDoesNotReceiveAnonymousPlaybackToken() {
        BroadcastRepository repository = mock(BroadcastRepository.class);
        Broadcast broadcast = broadcast(UUID.randomUUID(), UUID.randomUUID(), true, Instant.now());
        when(repository.findByPublicId(broadcast.publicId())).thenReturn(Optional.of(broadcast));
        BroadcastService service = new BroadcastService(repository, mock(CameraClient.class), mock(StreamManager.class),
                mock(PlaybackTokenService.class), new BroadcastProperties());

        org.junit.jupiter.api.Assertions.assertThrows(BroadcastService.PrivateBroadcastException.class,
                () -> service.playbackToken(broadcast.publicId()));
    }

    private static Broadcast broadcast(UUID id, UUID cameraId, boolean enabled, Instant now) {
        return new Broadcast(id, cameraId, UUID.randomUUID(), BroadcastVisibility.PRIVATE, enabled, 0, now, now);
    }
}
