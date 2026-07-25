package com.coltrack.searchservice.document;


import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;


@Data
@AllArgsConstructor
public class CameraDocument {


    private UUID cameraId;

    private String name;

    private String location;

    private Instant createdAt;

}
