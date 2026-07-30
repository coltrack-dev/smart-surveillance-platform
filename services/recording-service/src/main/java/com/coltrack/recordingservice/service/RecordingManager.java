package com.coltrack.recordingservice.service;


import com.coltrack.recordingservice.model.RecordingSession;
import com.coltrack.recordingservice.model.RecordingStatus;
import com.coltrack.recordingservice.worker.RecordingWorker;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


@Slf4j
@Service
public class RecordingManager {


    private final Map<UUID, RecordingSession> sessions =
            new ConcurrentHashMap<>();


    public RecordingSession start(
            UUID cameraId
    ) {


        RecordingSession session =
                RecordingSession.builder()
                        .id(UUID.randomUUID())
                        .cameraId(cameraId)
                        .status(RecordingStatus.STARTING)
                        .build();


        sessions.put(
                cameraId,
                session
        );


        Thread.startVirtualThread(
                new RecordingWorker(session)
        );


        log.info(
                "Recording worker started camera={}",
                cameraId
        );


        return session;
    }


    public RecordingSession find(
            UUID cameraId
    ) {

        return sessions.get(cameraId);
    }
}
