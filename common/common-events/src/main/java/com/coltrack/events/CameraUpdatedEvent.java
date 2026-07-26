package com.coltrack.events;


import java.util.UUID;


public record CameraUpdatedEvent(

        UUID cameraId,

        String name,

        String location

) {}
