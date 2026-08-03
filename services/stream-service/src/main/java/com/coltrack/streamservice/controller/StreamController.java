package com.coltrack.streamservice.controller;

import com.coltrack.streamservice.client.dto.CameraDto;
import com.coltrack.streamservice.dto.StreamResponse;
import com.coltrack.streamservice.model.StreamSession;
import com.coltrack.streamservice.service.StreamManager;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.UUID;

@RestController
@RequestMapping("/api/streams")
@RequiredArgsConstructor
public class StreamController {

    private final StreamManager manager;

    /**
     * Запуск RTSP -> FFmpeg -> HLS потока.
     */
    @PostMapping("/{cameraId}/start")
    public ResponseEntity<StreamResponse> start(
            @PathVariable UUID cameraId
    ) {

        StreamSession session =
                manager.start(cameraId);

        StreamResponse response =
                StreamResponse.builder()
                        .cameraId(session.getCameraId())
                        .status(session.getStatus())
                        .hlsUrl(
                                "/hls/"
                                        + cameraId
                                        + "/index.m3u8"
                        )
                        .startedAt(session.getStartedAt())
                        .reconnectCount(
                                session.getReconnectCount()
                        )
                        .lastError(
                                session.getLastError()
                        )
                        .build();

        return ResponseEntity.ok(response);
    }

    /**
     * Остановка FFmpeg процесса.
     */
    @PostMapping("/{cameraId}/stop")
    public ResponseEntity<Void> stop(
            @PathVariable UUID cameraId
    ) {

        manager.stop(cameraId);

        return ResponseEntity
                .noContent()
                .build();
    }

    /**
     * Получить список всех зарегистрированных камер.
     */
    @GetMapping("/cameras")
    public Collection<CameraDto> cameras() {

        return manager.findAvailableCameras();
    }

    /**
     * Получить состояние конкретного потока.
     */
    @GetMapping("/{cameraId}")
    public ResponseEntity<StreamResponse> get(
            @PathVariable UUID cameraId
    ) {

        StreamSession session =
                manager.find(cameraId);

        if (session == null) {

            return ResponseEntity.notFound()
                    .build();
        }

        return ResponseEntity.ok(
                StreamResponse.builder()
                        .cameraId(cameraId)
                        .status(session.getStatus())
                        .hlsUrl(
                                "/hls/"
                                        + cameraId
                                        + "/index.m3u8"
                        )
                        .startedAt(session.getStartedAt())
                        .reconnectCount(
                                session.getReconnectCount()
                        )
                        .lastError(
                                session.getLastError()
                        )
                        .build()
        );
    }

    /**
     * Получить все активные потоки.
     */
    @GetMapping
    public Collection<StreamSession> findAll() {

        return manager.findAll();
    }

    /**
     * Получить HLS URL потока.
     */
    @GetMapping("/{cameraId}/url")
    public ResponseEntity<String> getHlsUrl(
            @PathVariable UUID cameraId
    ) {

        StreamSession session =
                manager.find(cameraId);

        if (session == null) {
            return ResponseEntity
                    .notFound()
                    .build();
        }

        return ResponseEntity
                .ok(session.getHlsUrl());
    }

    /**
     * Удалить сессию из памяти.
     * Используется после остановки или ошибки.
     */
    @DeleteMapping("/{cameraId}")
    public ResponseEntity<Void> remove(
            @PathVariable UUID cameraId
    ) {

        manager.remove(cameraId);

        return ResponseEntity
                .noContent()
                .build();
    }
}
