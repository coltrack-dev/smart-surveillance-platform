package com.coltrack.events;


import java.util.UUID;


public record CameraDeletedEvent(

        UUID cameraId

) {}
