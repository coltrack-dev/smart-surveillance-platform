package com.coltrack.searchservice.document;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;


@Data
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CameraDocument {


    private UUID cameraId;

    private String name;

    private String location;

    private Instant createdAt;

}
