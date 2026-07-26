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
public class CameraEntity {


    @Id
    private UUID id;


    @Column(nullable = false)
    private String name;


    private String location;


    @Column(nullable = false)
    private Instant createdAt;


    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CameraStatus status;


    private Instant lastHeartbeat;



    public CameraEntity(
            UUID id,
            String name,
            String location
    ) {

        this.id = id;
        this.name = name;
        this.location = location;
        this.createdAt = Instant.now();
        this.status = CameraStatus.OFFLINE;
        this.lastHeartbeat = null;

    }

}
