package com.coltrack.streamservice.service;


import com.coltrack.streamservice.client.CameraClient;
import com.coltrack.streamservice.client.dto.CameraDto;
import com.coltrack.streamservice.model.StreamSession;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;


import java.time.Instant;
import java.util.Collection;
import java.util.UUID;


@Service
@RequiredArgsConstructor
@Slf4j
public class StreamService {

    private final CameraClient cameraClient;

    private final StreamManager sessionManager;


    public StreamSession start(
            UUID cameraId
    ) {

        CameraDto camera =
                cameraClient.findById(cameraId);

        return sessionManager.start(
                cameraId
                //,
                //camera.rtspUrl()
        );

    }


    public void stop(
            UUID cameraId
    ) {

        sessionManager.stop(cameraId);

    }


    public StreamSession find(
            UUID cameraId
    ) {

        return sessionManager.find(cameraId);

    }


    public Collection<StreamSession> findAll() {

        return sessionManager.findAll();

    }

}