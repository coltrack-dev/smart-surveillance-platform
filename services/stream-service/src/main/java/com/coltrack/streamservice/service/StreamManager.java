package com.coltrack.streamservice.service;

import com.coltrack.streamservice.model.StreamSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class StreamManager {

    private final CameraClient cameraClient;

    private final HlsService hlsService;

    private final Map<UUID, StreamSession> sessions =
            new ConcurrentHashMap<>();

}
