package com.coltrack.cameraservice.repository;


import com.coltrack.cameraservice.entity.CameraEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;


public interface CameraRepository
        extends JpaRepository<CameraEntity, UUID> {
}
