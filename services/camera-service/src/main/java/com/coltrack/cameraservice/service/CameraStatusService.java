package com.coltrack.cameraservice.service;

import com.coltrack.cameraservice.entity.CameraStatus;
import com.coltrack.cameraservice.repository.CameraRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;


@Service
@RequiredArgsConstructor
public class CameraStatusService {


    private final CameraRepository cameraRepository;


    @Transactional
    public void updateStatus(
            UUID cameraId,
            CameraStatus status,
            String reason
    ) {

        cameraRepository.updateStatus(
                cameraId,
                status,
                reason,
                Instant.now()
        );
    }
}
