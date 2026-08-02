package com.coltrack.recordingservice.repository;

import com.coltrack.recordingservice.model.RecordingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface RecordingRepository
        extends JpaRepository<RecordingEntity, UUID> {
}
