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
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Adapts the asynchronous Rust agent lifecycle to the existing StreamListener API.
 */
@Slf4j
@RequiredArgsConstructor
public class IngestAgentStreamWorker implements Runnable {

    private static final Duration MAX_RECONNECT_DELAY = Duration.ofSeconds(5);

    private final StreamSession session;
    private final IngestAgentClient client;
    private final IngestAgentProperties properties;
    private final StreamListener listener;

    @Override
    public void run() {
        session.setWorkerRunning(true);
        AgentPipelineState previousState = null;
        boolean stoppedPublished = false;
        boolean pipelineObserved = false;
        Duration reconnectDelay = properties.getPollInterval();
        AgentStartPipelineRequest request = startRequest();

        try {
            while (!session.isStopRequested()) {
                AgentPipelineStatus status;
                try {
                    if (!pipelineObserved) {
                        // Повторяется тот же request: если POST дошёл до агента,
                        // но ответ потерялся, commandId обеспечивает идемпотентность.
                        status = client.start(request);
                    } else {
                        Optional<AgentPipelineStatus> existing =
                                client.find(session.getCameraId());
                        if (existing.isPresent()) {
                            status = existing.get();
                        } else {
                            previousState = communicationReconnecting(
                                    previousState,
                                    "Ingest agent lost pipeline state"
                            );
                            status = client.start(request);
                        }
                    }
                    pipelineObserved = true;
                    reconnectDelay = properties.getPollInterval();
                } catch (ResourceAccessException | HttpServerErrorException communicationError) {
                    previousState = communicationReconnecting(
                            previousState,
                            "Ingest agent communication failed: " + communicationError.getMessage()
                    );
                    log.warn(
                            "Waiting to reconnect to ingest agent camera={}, delay={}",
                            session.getCameraId(),
                            reconnectDelay,
                            communicationError
                    );
                    sleep(reconnectDelay);
                    reconnectDelay = nextReconnectDelay(reconnectDelay);
                    continue;
                }

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
                sleep(properties.getPollInterval());
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
                agentVideoMode(session.getVideoProcessingMode()),
                AgentOutput.rtsp(properties.publishUrl(cameraId)),
                true
        );
    }

    static String agentVideoMode(VideoProcessingMode mode) {
        return switch (mode) {
            case COPY -> "COPY";
            case TRANSCODE_H264 -> "H264";
            // Агент выполнит ffprobe и сам выберет COPY для H.264 либо
            // перекодирование для HEVC и неизвестного кодека.
            case AUTO -> "AUTO";
        };
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

    private AgentPipelineState communicationReconnecting(
            AgentPipelineState previousState,
            String message
    ) {
        session.setStatus(StreamStatus.RECONNECTING);
        session.setLastError(message);
        if (previousState != AgentPipelineState.RECONNECTING) {
            listener.reconnecting(session);
        }
        return AgentPipelineState.RECONNECTING;
    }

    private Duration nextReconnectDelay(Duration current) {
        Duration doubled = current.multipliedBy(2);
        return doubled.compareTo(MAX_RECONNECT_DELAY) > 0
                ? MAX_RECONNECT_DELAY
                : doubled;
    }

    private void sleep(Duration duration) throws InterruptedException {
        Thread.sleep(duration);
    }
}
