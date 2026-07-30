package com.coltrack.cameraservice.controller;


import com.coltrack.cameraservice.dto.CreateCameraRequest;
import com.coltrack.cameraservice.entity.CameraEntity;
import com.coltrack.cameraservice.service.CameraMonitoringService;
import com.coltrack.cameraservice.service.CameraService;

import com.coltrack.cameraservice.service.RtspStreamChecker;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;


@RestController
@RequestMapping("/api/cameras")
@RequiredArgsConstructor
public class CameraController {


    private final CameraService service;
    private final CameraMonitoringService monitoringService;


    @PostMapping
    public ResponseEntity<CameraEntity> create(
            @RequestBody CreateCameraRequest request
    ) {

        CameraEntity camera =
                service.create(
                        request.name(),
                        request.location(),
                        request.rtspUrl(),
                        request.autoStart()
                );


        return ResponseEntity
                .created(
                        URI.create(
                                "/api/cameras/" + camera.getId()
                        )
                )
                .body(camera);
    }



    @GetMapping
    public List<CameraEntity> findAll() {

        return service.findAll();

    }



    @GetMapping("/{id}")
    public CameraEntity findById(
            @PathVariable UUID id
    ) {

        return service.findById(id);

    }



    @PutMapping("/{id}")
    public CameraEntity update(
            @PathVariable UUID id,
            @RequestBody CreateCameraRequest request
    ) {

        return service.update(
                id,
                request.name(),
                request.location(),
                request.rtspUrl()
        );
    }



    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable UUID id
    ) {

        service.delete(id);

    }

    @PostMapping("/{id}/heartbeat")
    public CameraEntity heartbeat(
            @PathVariable UUID id
    ) {

        return service.heartbeat(id);

    }

    @PostMapping("/{id}/check-stream")
    public CameraEntity checkStream(
            @PathVariable UUID id
    ) {

        return monitoringService.checkCamera(id);

    }
}
