package com.coltrack.searchservice.service;


import com.coltrack.events.CameraRegisteredEvent;
import com.coltrack.searchservice.document.CameraDocument;
import com.coltrack.searchservice.repository.OpenSearchCameraRepository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class CameraIndexServiceImpl
        implements CameraIndexService {


    private final OpenSearchCameraRepository repository;


    public CameraIndexServiceImpl(
            OpenSearchCameraRepository repository
    ) {
        this.repository = repository;
    }

    @Override
    public void index(
            CameraRegisteredEvent event
    ) {

        CameraDocument document =
                new CameraDocument(
                        event.cameraId(),
                        event.name(),
                        event.location(),
                        event.createdAt()
                );


        repository.save(document);

        log.info("FINISHED INDEX {}", event.cameraId());

    }
}
