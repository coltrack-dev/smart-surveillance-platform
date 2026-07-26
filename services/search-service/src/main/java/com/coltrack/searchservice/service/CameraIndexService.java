package com.coltrack.searchservice.service;

import com.coltrack.events.CameraDeletedEvent;
import com.coltrack.events.CameraHeartbeatEvent;
import com.coltrack.events.CameraRegisteredEvent;
import com.coltrack.events.CameraUpdatedEvent;

public interface CameraIndexService {


    void index(
            CameraRegisteredEvent event
    );


    void update(
            CameraUpdatedEvent event
    );


    void delete(
            CameraDeletedEvent event
    );

    void updateHeartbeat(CameraHeartbeatEvent event);
}
