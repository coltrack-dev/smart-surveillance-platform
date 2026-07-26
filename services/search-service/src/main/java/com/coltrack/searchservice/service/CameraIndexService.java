package com.coltrack.searchservice.service;

import com.coltrack.events.*;

public interface CameraIndexService {


    void index(CameraRegisteredEvent event);

    void update(CameraUpdatedEvent event);

    void delete(CameraDeletedEvent event);

    void updateStatus(CameraStatusChangedEvent event);

    void updateHeartbeat(CameraHeartbeatEvent event);

}
