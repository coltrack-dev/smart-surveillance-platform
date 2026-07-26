package com.coltrack.searchservice.service;


import com.coltrack.events.*;
import com.coltrack.searchservice.document.CameraDocument;
import com.coltrack.searchservice.repository.OpenSearchCameraRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;


@Service
@RequiredArgsConstructor
public class CameraIndexServiceImpl
        implements CameraIndexService {


    private final OpenSearchCameraRepository repository;


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

    }


    @Override
    public void update(
            CameraUpdatedEvent event
    ) {


        CameraDocument document =
                new CameraDocument(
                        event.cameraId(),
                        event.name(),
                        event.location(),
                        null
                );


        repository.save(document);


    }


    @Override
    public void delete(
            CameraDeletedEvent event
    ) {


        repository.delete(
                event.cameraId()
        );

    }

}
