package com.coltrack.streamservice.worker;

import com.coltrack.streamservice.client.IngestAgentClient;
import com.coltrack.streamservice.client.dto.agent.AgentOutput;
import com.coltrack.streamservice.client.dto.agent.AgentPipelineState;
import com.coltrack.streamservice.client.dto.agent.AgentPipelineStatus;
import com.coltrack.streamservice.client.dto.agent.AgentStartPipelineRequest;
import com.coltrack.streamservice.config.IngestAgentProperties;
import com.coltrack.streamservice.model.StreamSession;
import com.coltrack.streamservice.model.StreamStatus;
import com.coltrack.streamservice.model.VideoProcessingMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Adapts the asynchronous Rust agent lifecycle to the existing StreamListener API.
 */
@Slf4j
@RequiredArgsConstructor
public class IngestAgentStreamWorker implements Runnable {

    private final StreamSession session;
    private final IngestAgentClient client;
    private final IngestAgentProperties properties;
    private final StreamListener listener;

    @Override
    public void run() {
        session.setWorkerRunning(true);
        AgentPipelineState previousState = null;
        boolean stoppedPublished = false;

        try {
            AgentPipelineStatus status = client.start(startRequest());

            while (!session.isStopRequested()) {
                applyStatus(status, previousState);

                if (status.state() == AgentPipelineState.FAILED) {
                    return;
                }

                if (status.state() == AgentPipelineState.STOPPED) {
                    listener.stopped(session);
                    stoppedPublished = true;
                    return;
                }

                previousState = status.state();
                sleep();

                if (!session.isStopRequested()) {
                    status = client.get(session.getCameraId());
                }
            }

            client.stop(session.getCameraId());
            listener.stopped(session);
            stoppedPublished = true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            session.setLastError("Ingest agent worker was interrupted");
            listener.failed(session);
        } catch (Exception error) {
            session.setLastError("Ingest agent communication failed: " + error.getMessage());
            log.error("Ingest agent worker failed camera={}", session.getCameraId(), error);
            listener.failed(session);
        } finally {
            session.setWorkerRunning(false);
            if (session.isStopRequested() && !stoppedPublished) {
                log.warn(
                        "Stream stop was not confirmed by ingest agent camera={}",
                        session.getCameraId()
                );
            }
        }
    }

    private AgentStartPipelineRequest startRequest() {
        UUID cameraId = session.getCameraId();
        return new AgentStartPipelineRequest(
                UUID.randomUUID(),
                cameraId,
                session.getRtspUrl(),
                "TCP",
                agentVideoMode(session.getVideoProcessingMode(), properties.getAutoVideoMode()),
                AgentOutput.rtsp(properties.publishUrl(cameraId)),
                true
        );
    }

    static String agentVideoMode(VideoProcessingMode mode, String autoVideoMode) {
        String selected = switch (mode) {
            case COPY -> "COPY";
            case TRANSCODE_H264 -> "H264";
            case AUTO -> autoVideoMode.toUpperCase(Locale.ROOT);
        };
        if (!selected.equals("COPY") && !selected.equals("H264")) {
            throw new IllegalArgumentException(
                    "stream.ingest-agent.auto-video-mode must be COPY or H264"
            );
        }
        return selected;
    }

    private void applyStatus(AgentPipelineStatus status, AgentPipelineState previousState) {
        session.setReconnectCount(status.restartCount());
        session.setLastError(status.lastError());

        if (status.probe() != null) {
            session.setDetectedVideoCodec(status.probe().codec());
        }

        switch (status.state()) {
            case STARTING -> session.setStatus(StreamStatus.STARTING);
            case RUNNING -> {
                session.setStatus(StreamStatus.RUNNING);
                if (session.getStartedAt() == null) {
                    session.setStartedAt(Instant.now());
                }
                if (previousState != AgentPipelineState.RUNNING) {
                    listener.started(session);
                }
            }
            case RECONNECTING -> {
                session.setStatus(StreamStatus.RECONNECTING);
                if (previousState != AgentPipelineState.RECONNECTING) {
                    listener.reconnecting(session);
                }
            }
            case STOPPING -> session.setStatus(StreamStatus.STOPPING);
            case STOPPED -> session.setStatus(StreamStatus.STOPPED);
            case FAILED -> {
                session.setStatus(StreamStatus.ERROR);
                listener.failed(session);
            }
        }
    }

    private void sleep() throws InterruptedException {
        Thread.sleep(properties.getPollInterval());
    }
}
