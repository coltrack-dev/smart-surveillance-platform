package com.coltrack.searchservice.service;

import com.coltrack.events.CameraRegisteredEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class CameraIndexServiceImpl implements CameraIndexService {

    @Override
    public void index(CameraRegisteredEvent event) {

        log.info("Index camera {}", event);

    }
}
