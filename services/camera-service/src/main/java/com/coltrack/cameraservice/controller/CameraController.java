package com.coltrack.cameraservice.controller;


import com.coltrack.cameraservice.entity.CameraEntity;
import com.coltrack.cameraservice.service.CameraService;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;


@RestController
@RequestMapping("/api/cameras")
@RequiredArgsConstructor
public class CameraController {


    private final CameraService service;


    @PostMapping
    public CameraEntity create(
            @RequestParam String name,
            @RequestParam String location
    ) {

        return service.create(
                name,
                location
        );
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
            @RequestParam String name,
            @RequestParam String location
    ) {

        return service.update(
                id,
                name,
                location
        );

    }


    @DeleteMapping("/{id}")
    public void delete(
            @PathVariable UUID id
    ) {

        service.delete(id);

    }

}
