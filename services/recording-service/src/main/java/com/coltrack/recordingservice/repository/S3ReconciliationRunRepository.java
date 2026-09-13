package com.coltrack.recordingservice.repository;

import com.coltrack.recordingservice.model.S3ReconciliationRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface S3ReconciliationRunRepository
        extends JpaRepository<S3ReconciliationRunEntity, UUID> {

    Optional<S3ReconciliationRunEntity> findTopByOrderByCheckedAtDesc();
}
