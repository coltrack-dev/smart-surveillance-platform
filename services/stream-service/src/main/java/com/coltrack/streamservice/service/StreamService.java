package com.coltrack.streamservice.service;


import com.coltrack.streamservice.model.StreamSession;
import com.coltrack.streamservice.model.StreamStatus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;


import java.time.Instant;
import java.util.UUID;


@Slf4j
@Service
@RequiredArgsConstructor
public class StreamService {


    private final StreamSessionManager manager;


    public void start(
            UUID cameraId,
            String rtspUrl
    ) {


        log.info(
                "Starting stream camera={} url={}",
                cameraId,
                rtspUrl
        );


        StreamSession session =
                StreamSession.builder()
                        .cameraId(cameraId)
                        .rtspUrl(rtspUrl)
                        .status(StreamStatus.STARTING)
                        .startedAt(Instant.now())
                        .build();


        manager.add(session);


        /*
          Здесь позже будет:

          FFmpeg process
          RTSP connection
          HLS generation
        */


        session.setStatus(
                StreamStatus.RUNNING
        );


        log.info(
                "Stream started camera={}",
                cameraId
        );

    }

}
