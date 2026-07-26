package com.coltrack.cameraservice.dto;


public record CreateCameraRequest(

        String name,

        String location,

        String rtspUrl
) {}
