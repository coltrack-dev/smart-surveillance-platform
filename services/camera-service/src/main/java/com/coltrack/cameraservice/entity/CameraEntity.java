package com.coltrack.cameraservice.entity;


import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;


@Entity
@Table(name = "cameras")
public class CameraEntity {


    @Id
    private UUID id;


    private String name;


    private String location;


    private Instant createdAt;


    protected CameraEntity() {
    }


    public CameraEntity(
            UUID id,
            String name,
            String location
    ) {
        this.id = id;
        this.name = name;
        this.location = location;
        this.createdAt = Instant.now();
    }


    public UUID getId() {
        return id;
    }


    public String getName() {
        return name;
    }


    public String getLocation() {
        return location;
    }


    public Instant getCreatedAt() {
        return createdAt;
    }
}
