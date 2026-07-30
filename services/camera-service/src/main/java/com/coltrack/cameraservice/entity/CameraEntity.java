package com.coltrack.cameraservice.entity;


import jakarta.persistence.*;

import lombok.*;

import java.time.Instant;
import java.util.UUID;


@Entity
@Table(name = "cameras")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CameraEntity {


    @Id
    private UUID id;


    @Column(nullable = false)
    private String name;


    private String location;

    private String rtspUrl;

    @Column(nullable = false)
    private Instant createdAt;


    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CameraStatus status = CameraStatus.OFFLINE;


    private Instant lastHeartbeat;

    @Column(nullable = false)
    @Builder.Default
    private boolean autoStart = false;

    public CameraEntity(
            UUID id,
            String name,
            String location,
            String rtspUrl
    ) {

        this.id = id;
        this.name = name;
        this.location = location;
        this.rtspUrl = rtspUrl;
        this.createdAt = Instant.now();
        this.status = CameraStatus.OFFLINE;
        this.lastHeartbeat = null;

    }

}
