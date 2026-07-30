package com.coltrack.streamservice.controller;

import com.coltrack.streamservice.model.StreamSession;
import com.coltrack.streamservice.model.StreamStatus;
import com.coltrack.streamservice.service.StreamManager;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;


@Slf4j
@RestController
@RequestMapping("/api/streams")
@RequiredArgsConstructor
public class StreamHealthController {


    private final StreamManager streamManager;


    /**
     * Returns stream health information.
     * <p>
     * Checks:
     * - session exists;
     * - FFmpeg process state;
     * - last received frame time.
     */
    @GetMapping("/{cameraId}/health")
    public Map<String, Object> health(
            @PathVariable UUID cameraId
    ) {

        log.info(
                "Checking stream health camera={}",
                cameraId
        );


        StreamSession session =
                streamManager.find(cameraId);


        if (session == null) {

            log.warn(
                    "Stream session not found camera={}",
                    cameraId
            );

            return Map.of(
                    "cameraId",
                    cameraId,
                    "status",
                    "NOT_FOUND",
                    "healthy",
                    false
            );
        }


        Process process =
                session.getFfmpegProcess();


        boolean ffmpegAlive =
                process != null &&
                        process.isAlive();


        boolean framesAvailable =
                session.getLastFrameTime() != null &&
                        Duration.between(
                                session.getLastFrameTime(),
                                Instant.now()
                        ).getSeconds() < 10;


        boolean healthy =
                session.getStatus() == StreamStatus.RUNNING
                        &&
                        ffmpegAlive
                        &&
                        framesAvailable;


        return Map.of(

                "cameraId",
                cameraId,

                "status",
                session.getStatus(),

                "ffmpegAlive",
                ffmpegAlive,

                "lastFrameTime",
                session.getLastFrameTime(),

                "reconnectCount",
                session.getReconnectCount(),

                "healthy",
                healthy
        );
    }
}
