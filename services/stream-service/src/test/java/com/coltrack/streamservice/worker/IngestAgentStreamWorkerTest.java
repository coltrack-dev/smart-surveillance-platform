package com.coltrack.streamservice.worker;

import com.coltrack.streamservice.client.IngestAgentClient;
import com.coltrack.streamservice.client.MediaHlsClient;
import com.coltrack.streamservice.client.dto.agent.AgentPipelineState;
import com.coltrack.streamservice.client.dto.agent.AgentPipelineStatus;
import com.coltrack.streamservice.client.dto.agent.AgentStartPipelineRequest;
import com.coltrack.streamservice.config.IngestAgentProperties;
import com.coltrack.streamservice.model.StreamSession;
import com.coltrack.streamservice.model.StreamStatus;
import com.coltrack.streamservice.model.VideoProcessingMode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestAgentStreamWorkerTest {

    @Test
    void mapsExistingVideoModesToAgentContract() {
        assertEquals(
                "COPY",
                IngestAgentStreamWorker.agentVideoMode(VideoProcessingMode.COPY)
        );
        assertEquals(
                "H264",
                IngestAgentStreamWorker.agentVideoMode(VideoProcessingMode.TRANSCODE_H264)
        );
        assertEquals(
                "AUTO",
                IngestAgentStreamWorker.agentVideoMode(VideoProcessingMode.AUTO)
        );
        assertEquals("AUTO", IngestAgentStreamWorker.agentVideoMode(null));
    }

    @Test
    void recreatesMissingPipelineWithTheSameCommandId() {
        UUID cameraId = UUID.randomUUID();
        StreamSession session = session(cameraId);
        IngestAgentClient client = mock(IngestAgentClient.class);
        MediaHlsClient mediaHlsClient = readyHlsClient(cameraId);
        StreamListener listener = mock(StreamListener.class);
        IngestAgentProperties properties = properties();
        AgentPipelineStatus starting = status(AgentPipelineState.STARTING);
        AgentPipelineStatus running = status(AgentPipelineState.RUNNING);
        AgentPipelineStatus stopped = status(AgentPipelineState.STOPPED);

        when(client.find(cameraId)).thenReturn(
                Optional.of(running),
                Optional.empty(),
                Optional.of(stopped)
        );
        when(client.start(any())).thenReturn(starting);

        new IngestAgentStreamWorker(
                session, client, mediaHlsClient, properties, listener
        ).run();

        ArgumentCaptor<AgentStartPipelineRequest> requests =
                ArgumentCaptor.forClass(AgentStartPipelineRequest.class);
        verify(client, times(2)).start(requests.capture());
        assertEquals(
                requests.getAllValues().get(0).commandId(),
                requests.getAllValues().get(1).commandId()
        );
        verify(listener).reconnecting(session);
        verify(listener).started(session);
        verify(listener).stopped(session);
    }

    @Test
    void retriesAfterTemporaryAgentCommunicationFailure() {
        UUID cameraId = UUID.randomUUID();
        StreamSession session = session(cameraId);
        IngestAgentClient client = mock(IngestAgentClient.class);
        MediaHlsClient mediaHlsClient = readyHlsClient(cameraId);
        StreamListener listener = mock(StreamListener.class);
        IngestAgentProperties properties = properties();
        AgentPipelineStatus starting = status(AgentPipelineState.STARTING);
        AgentPipelineStatus running = status(AgentPipelineState.RUNNING);
        AgentPipelineStatus stopped = status(AgentPipelineState.STOPPED);

        when(client.find(cameraId))
                .thenThrow(new ResourceAccessException("connection refused"))
                .thenReturn(Optional.of(running))
                .thenReturn(Optional.of(stopped));
        when(client.start(any())).thenReturn(starting);

        new IngestAgentStreamWorker(
                session, client, mediaHlsClient, properties, listener
        ).run();

        verify(client, times(3)).find(cameraId);
        verify(client).start(any());
        verify(listener).reconnecting(session);
        verify(listener).started(session);
        verify(listener).stopped(session);
    }

    @Test
    void doesNotPublishRunningBeforeHlsIsReady() {
        UUID cameraId = UUID.randomUUID();
        StreamSession session = session(cameraId);
        IngestAgentClient client = mock(IngestAgentClient.class);
        MediaHlsClient mediaHlsClient = mock(MediaHlsClient.class);
        StreamListener listener = mock(StreamListener.class);
        IngestAgentProperties properties = properties();
        AgentPipelineStatus starting = status(AgentPipelineState.STARTING);
        AgentPipelineStatus running = status(AgentPipelineState.RUNNING);
        AgentPipelineStatus stopped = status(AgentPipelineState.STOPPED);

        when(client.start(any())).thenReturn(starting);
        when(client.find(cameraId)).thenReturn(
                Optional.of(running),
                Optional.of(running),
                Optional.of(stopped)
        );
        when(mediaHlsClient.isReady(cameraId)).thenReturn(false, true);

        new IngestAgentStreamWorker(
                session, client, mediaHlsClient, properties, listener
        ).run();

        verify(mediaHlsClient, times(2)).isReady(cameraId);
        verify(listener, times(1)).started(session);
        verify(listener).stopped(session);
    }

    private StreamSession session(UUID cameraId) {
        return StreamSession.builder()
                .cameraId(cameraId)
                .rtspUrl("rtsp://camera.test/live")
                .videoProcessingMode(VideoProcessingMode.AUTO)
                .status(StreamStatus.STARTING)
                .build();
    }

    private IngestAgentProperties properties() {
        IngestAgentProperties properties = new IngestAgentProperties();
        properties.setPollInterval(Duration.ZERO);
        properties.setPublishRtspBaseUrl("rtsp://mediamtx:8554");
        properties.setHlsReadyTimeout(Duration.ofSeconds(1));
        properties.setHlsHealthInterval(Duration.ofDays(1));
        return properties;
    }

    private MediaHlsClient readyHlsClient(UUID cameraId) {
        MediaHlsClient client = mock(MediaHlsClient.class);
        when(client.isReady(cameraId)).thenReturn(true);
        return client;
    }

    private AgentPipelineStatus status(AgentPipelineState state) {
        AgentPipelineStatus status = mock(AgentPipelineStatus.class);
        when(status.state()).thenReturn(state);
        return status;
    }
}
