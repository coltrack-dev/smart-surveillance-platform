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
                        "OFFLINE",
                        event.createdAt(),
                        null
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
                        null,
                        event.updatedAt(),
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



    @Override
    public void updateStatus(
            CameraStatusChangedEvent event
    ) {


        repository.updateStatus(
                event.cameraId(),
                event.status(),
                event.changedAt()
        );

    }

    @Override
    public void updateHeartbeat(
            CameraHeartbeatEvent event
    ) {


        repository.updateHeartbeat(
                event.cameraId(),
                event.timestamp()
        );

    }
}
