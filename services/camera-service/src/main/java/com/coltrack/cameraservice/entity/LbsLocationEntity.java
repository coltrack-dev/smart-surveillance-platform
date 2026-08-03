package com.coltrack.cameraservice.entity;


import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name="lbs_locations")
@Getter
@Setter
public class LbsLocationEntity {


    @Id
    private UUID id;


    private String name;


    private Double latitude;


    private Double longitude;


    private String address;


    private String description;

}
