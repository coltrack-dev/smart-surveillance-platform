package com.coltrack.cameraservice.controller;


import com.coltrack.cameraservice.entity.CameraEntity;
import com.coltrack.cameraservice.dto.CreateCameraRequest;
import com.coltrack.cameraservice.service.CameraService;

import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/api/cameras")
public class CameraController {


    private final CameraService service;


    public CameraController(CameraService service) {
        this.service = service;
    }


    @PostMapping
    public CameraEntity create(
            @RequestBody CreateCameraRequest request
    ) {

        return service.create(
                request.name(),
                request.location()
        );
    }
}
