package com.coltrack.searchservice.document;


import lombok.*;

import java.time.Instant;
import java.util.UUID;


@Data
@AllArgsConstructor
@NoArgsConstructor
public class CameraDocument {


    private UUID cameraId;


    private String name;


    private String location;


    private String status;


    private Instant createdAt;


    private Instant lastHeartbeat;

}
