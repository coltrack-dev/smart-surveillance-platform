package com.coltrack.recordingservice.repository;

import com.coltrack.recordingservice.model.RecordingObjectEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RecordingObjectRepository
        extends JpaRepository<RecordingObjectEntity, UUID> {

    List<RecordingObjectEntity>
    findByRecordingIdOrderBySequenceNumberAsc(
            UUID recordingId
    );

    boolean existsByS3Key(
            String s3Key
    );

}
