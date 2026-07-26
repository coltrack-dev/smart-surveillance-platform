package com.coltrack.searchservice.document;


import lombok.*;

import java.time.Instant;
import java.util.UUID;


@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CameraDocument {


    private UUID cameraId;

    private String name;

    private String location;

    private String rtspUrl;

    private String status;

    private Instant createdAt;

    private Instant lastHeartbeat;

}
