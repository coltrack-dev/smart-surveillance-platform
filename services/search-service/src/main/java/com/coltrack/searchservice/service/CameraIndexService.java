package com.coltrack.searchservice.service;

import com.coltrack.events.CameraRegisteredEvent;

public interface CameraIndexService {

    void index(CameraRegisteredEvent event);

}
