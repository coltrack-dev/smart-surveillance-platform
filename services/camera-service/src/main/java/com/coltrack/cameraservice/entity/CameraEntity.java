package com.coltrack.cameraservice.entity;


import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;


@Entity
@Table(name = "cameras")
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class CameraEntity {


    @Id
    private UUID id;


    private String name;


    private String location;


    private Instant createdAt;

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
