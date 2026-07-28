package com.coltrack.streamservice.service;

import com.coltrack.streamservice.model.StreamSession;
import com.coltrack.streamservice.model.StreamStatus;
import com.coltrack.streamservice.worker.CameraStreamWorker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class StreamSessionManager {

    private final Map<UUID, StreamSession> sessions =
            new ConcurrentHashMap<>();


    public StreamSession start(
            UUID cameraId,
            String rtspUrl
    ) {

        StreamSession existing =
                sessions.get(cameraId);

        if (existing != null &&
                existing.getStatus() == StreamStatus.RUNNING) {

            return existing;
        }

        StreamSession session =
                StreamSession.builder()
                        .cameraId(cameraId)
                        .rtspUrl(rtspUrl)
                        .status(StreamStatus.STARTING)
                        .build();

        sessions.put(
                cameraId,
                session
        );

        Thread.startVirtualThread(
                new CameraStreamWorker(session)
        );

        log.info(
                "Starting stream {}",
                cameraId
        );

        return session;
    }


    public void stop(
            UUID cameraId
    ) {

        StreamSession session =
                sessions.get(cameraId);

        if (session == null) {
            return;
        }

        Process process =
                session.getFfmpegProcess();

        if (process != null) {

            process.destroy();

        }

        session.setStatus(
                StreamStatus.STOPPED
        );

        sessions.remove(cameraId);

        log.info(
                "Stopped stream {}",
                cameraId
        );

    }


    public StreamSession find(
            UUID cameraId
    ) {

        return sessions.get(cameraId);

    }


    public Collection<StreamSession> findAll() {

        return sessions.values();

    }


    public void remove(
            UUID cameraId
    ) {

        sessions.remove(cameraId);

    }
}
