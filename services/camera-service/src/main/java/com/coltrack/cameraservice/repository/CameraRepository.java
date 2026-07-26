package com.coltrack.cameraservice.repository;


import com.coltrack.cameraservice.entity.CameraEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;


public interface CameraRepository
        extends JpaRepository<CameraEntity, UUID> {

    List<CameraEntity> findByLastHeartbeatBefore(
            Instant time
    );
}
