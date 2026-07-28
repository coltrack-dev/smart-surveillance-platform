package com.coltrack.streamservice.controller;

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


    @PostMapping("/{cameraId}/start")
    public ResponseEntity<StreamSession> start(
            @PathVariable UUID cameraId
    ) {

        StreamSession session =
                manager.start(cameraId);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(session);

    }


    @PostMapping("/{cameraId}/stop")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void stop(
            @PathVariable UUID cameraId
    ) {

        manager.stop(cameraId);

    }


    @GetMapping("/{cameraId}")
    public ResponseEntity<StreamSession> get(
            @PathVariable UUID cameraId
    ) {

        StreamSession session =
                manager.find(cameraId);

        if (session == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(session);

    }


    @GetMapping
    public Collection<StreamSession> findAll() {

        return manager.findAll();

    }

}
