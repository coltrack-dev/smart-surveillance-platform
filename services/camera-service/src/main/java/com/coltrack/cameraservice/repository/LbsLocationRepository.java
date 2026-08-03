package com.coltrack.cameraservice.repository;

import com.coltrack.cameraservice.entity.LbsLocationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;


public interface LbsLocationRepository
        extends JpaRepository<LbsLocationEntity, UUID> {

}
