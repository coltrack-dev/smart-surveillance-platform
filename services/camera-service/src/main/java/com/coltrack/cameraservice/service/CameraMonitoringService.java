package com.coltrack.cameraservice.service;

import com.coltrack.cameraservice.entity.CameraEntity;
import com.coltrack.cameraservice.entity.CameraStatus;
import com.coltrack.cameraservice.repository.CameraRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CameraMonitoringService {


    private final CameraRepository repository;

    private final RtspStreamChecker checker;


    public CameraEntity checkCamera(UUID id) {


        CameraEntity camera =
                repository.findById(id)
                        .orElseThrow();


        boolean online =
                checker.check(
                        camera.getRtspUrl()
                );


        camera.setStatus(
                online
                        ? CameraStatus.ONLINE
                        : CameraStatus.ERROR
        );


        camera.setLastHeartbeat(
                Instant.now()
        );


        return repository.save(camera);

    }


    public void checkAll() {

        repository.findAll()
                .forEach(
                        camera ->
                                checkCamera(camera.getId())
                );

    }
}
