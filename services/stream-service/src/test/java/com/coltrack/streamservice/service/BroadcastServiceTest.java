package com.coltrack.streamservice.service;

import com.coltrack.streamservice.client.CameraClient;
import com.coltrack.streamservice.config.BroadcastProperties;
import com.coltrack.streamservice.model.Broadcast;
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
    void enablingBroadcastReusesAnAlreadyRunningStream() {
        BroadcastRepository repository = mock(BroadcastRepository.class);
        CameraClient cameraClient = mock(CameraClient.class);
        StreamManager streamManager = mock(StreamManager.class);
        PlaybackTokenService tokenService = mock(PlaybackTokenService.class);
        BroadcastProperties properties = new BroadcastProperties();
        UUID broadcastId = UUID.randomUUID();
        UUID cameraId = UUID.randomUUID();
        Instant now = Instant.now();
        Broadcast broadcast = new Broadcast(broadcastId, cameraId, false, false, 0, now, now);
        when(repository.find(broadcastId)).thenReturn(Optional.of(broadcast));
        when(streamManager.find(cameraId)).thenReturn(StreamSession.builder().cameraId(cameraId).workerRunning(true).build());
        when(repository.setState(eq(broadcastId), eq(true), eq(false), any())).thenReturn(
                new Broadcast(broadcastId, cameraId, true, false, 1, now, now));
        when(repository.find(broadcastId)).thenReturn(Optional.of(broadcast),
                Optional.of(new Broadcast(broadcastId, cameraId, true, false, 1, now, now)));

        new BroadcastService(repository, cameraClient, streamManager, tokenService, properties)
                .setEnabled(broadcastId, true);

        verify(streamManager, never()).start(cameraId);
        verify(repository).setState(eq(broadcastId), eq(true), eq(false), any());
    }
}
