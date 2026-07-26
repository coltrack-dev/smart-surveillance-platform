package com.coltrack.cameraservice.scheduler;

import com.coltrack.cameraservice.service.CameraMonitoringService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CameraHealthScheduler {


    private final CameraMonitoringService service;


    @Scheduled(fixedDelay = 10000)
    public void checkCameras() {

        service.checkAll();

    }
}
