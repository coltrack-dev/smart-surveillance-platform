package com.coltrack.cameraservice.repository;


import com.coltrack.cameraservice.entity.CameraEntity;
import com.coltrack.cameraservice.entity.CameraStatus;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;


public interface CameraRepository
        extends JpaRepository<CameraEntity, UUID> {


    List<CameraEntity> findByStatus(
            CameraStatus status
    );

    @Modifying
    @Query("""
                update CameraEntity c
                   set c.status = :status,
                       c.lastError = :reason,
                       c.lastStatusChangedAt = :changedAt
                 where c.id = :cameraId
            """)
    int updateStatus(
            @Param("cameraId") UUID cameraId,
            @Param("status") CameraStatus status,
            @Param("reason") String reason,
            @Param("changedAt") Instant changedAt
    );

    @Modifying
    @Query("""
                update CameraEntity c
                   set c.status = :status,
                       c.lastError = :reason
                 where c.id = :cameraId
            """)
    int updateError(
            @Param("cameraId") UUID cameraId,
            @Param("status") CameraStatus status,
            @Param("reason") String reason
    );

    @Modifying
    @Query("""
                update CameraEntity c
                   set c.status = :status,
                       c.lastError = null
                 where c.id = :cameraId
            """)
    int updateOnline(
            @Param("cameraId") UUID cameraId,
            @Param("status") CameraStatus status
    );

    @Query(
            value = "select nextval('camera_number_seq')",
            nativeQuery = true
    )
    Integer nextCameraNumber();

}
