package com.coltrack.recordingservice.repository;

import com.coltrack.recordingservice.model.S3ReconciliationIssueEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface S3ReconciliationIssueRepository
        extends JpaRepository<S3ReconciliationIssueEntity, UUID> {

    Optional<S3ReconciliationIssueEntity> findByS3Key(String s3Key);

    List<S3ReconciliationIssueEntity> findByResolvedAtIsNullOrderByLastCheckedAtDesc();
}
