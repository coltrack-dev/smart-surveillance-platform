package com.coltrack.streamservice.service;


import com.coltrack.streamservice.client.CameraClient;
import com.coltrack.streamservice.client.dto.CameraDto;
import com.coltrack.streamservice.model.StreamSession;
import com.coltrack.streamservice.model.StreamStatus;
import com.coltrack.streamservice.worker.CameraStreamWorker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


@Slf4j
@Service
@RequiredArgsConstructor
public class StreamManager {


    private final CameraClient cameraClient;

    private final HlsService hlsService;


    private final Map<UUID, StreamSession> sessions =
            new ConcurrentHashMap<>();


    public StreamSession start(
            UUID cameraId
    ) {

        StreamSession existing =
                sessions.get(cameraId);

        if (existing != null &&
                existing.getStatus() == StreamStatus.RUNNING) {

            log.info("Stream already running camera={}", cameraId);

            return existing;
        }

        CameraDto camera = cameraClient.findById(cameraId);

        StreamSession session =
                StreamSession.builder()
                        .cameraId(camera.id())
                        .rtspUrl(camera.rtspUrl())
                        .status(StreamStatus.STARTING)
                        .build();

        sessions.put(
                cameraId,
                session
        );

        Thread.startVirtualThread(
                new CameraStreamWorker(
                        session,
                        hlsService
                )
        );

        log.info("Stream starting camera={}", cameraId);

        return session;
    }

    public void stop(UUID cameraId) {

        StreamSession session =
                sessions.get(cameraId);


        if (session == null) {

            log.warn(
                    "Stream not found camera={}",
                    cameraId
            );

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

        log.info("Stream stopped camera={}", cameraId);
    }


    public StreamSession find(UUID cameraId) {

        return sessions.get(cameraId);
    }


    public Collection<StreamSession> findAll() {

        return sessions.values();
    }


    public void remove(UUID cameraId) {

        sessions.remove(cameraId);
    }

    public Collection<CameraDto> findAvailableCameras() {

        return cameraClient.findAll();
    }
}
