package com.coltrack.streamservice.service;


import com.coltrack.streamservice.model.StreamSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;


import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


@Slf4j
@Component
public class StreamSessionManager {


    private final Map<UUID, StreamSession> sessions =
            new ConcurrentHashMap<>();


    public void add(
            StreamSession session
    ) {

        sessions.put(
                session.getCameraId(),
                session
        );


        log.info(
                "Stream session added: {}",
                session.getCameraId()
        );

    }


    public StreamSession get(
            UUID cameraId
    ) {

        return sessions.get(cameraId);

    }


    public void remove(
            UUID cameraId
    ) {

        sessions.remove(cameraId);

    }


    public Map<UUID, StreamSession> getAll() {

        return sessions;

    }

}
