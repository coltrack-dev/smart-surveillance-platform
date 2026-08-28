package com.coltrack.cameraservice.controller;

import com.coltrack.cameraservice.dto.CameraConnectionResponse;
import com.coltrack.cameraservice.service.CameraService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/internal/cameras")
@RequiredArgsConstructor
public class CameraInternalController {

    private final CameraService cameraService;

    @GetMapping("/{id}/connection")
    public CameraConnectionResponse connection(@PathVariable UUID id) {
        return cameraService.connection(id);
    }
}
