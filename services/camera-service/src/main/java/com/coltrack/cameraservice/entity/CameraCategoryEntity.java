package com.coltrack.cameraservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "camera_categories")
@Getter
@Setter
public class CameraCategoryEntity {


    @Id
    private UUID id;


    @Column(nullable = false, unique = true)
    private String name;


    private String description;


    @Column(nullable = false)
    private boolean favoriteCategory = false;
}
