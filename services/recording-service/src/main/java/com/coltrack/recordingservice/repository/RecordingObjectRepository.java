package com.coltrack.recordingservice.repository;

import com.coltrack.recordingservice.model.RecordingObjectEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    boolean existsByRecordingId(
            UUID recordingId
    );

    @Query("""
            select o from RecordingObjectEntity o
            where o.recordingId = :recordingId
              and (o.cleanupStatus is null or o.cleanupStatus <> com.coltrack.recordingservice.model.S3ObjectCleanupStatus.DELETED)
            order by o.sequenceNumber asc
            """)
    List<RecordingObjectEntity> findActiveByRecordingId(
            @Param("recordingId") UUID recordingId
    );

    @Query("""
            select case when count(o) > 0 then true else false end
            from RecordingObjectEntity o
            where o.recordingId = :recordingId
              and (o.cleanupStatus is null or o.cleanupStatus <> com.coltrack.recordingservice.model.S3ObjectCleanupStatus.DELETED)
            """)
    boolean existsActiveByRecordingId(
            @Param("recordingId") UUID recordingId
    );

    @Query("""
            select coalesce(sum(o.sizeBytes), 0)
            from RecordingObjectEntity o
            where o.cleanupStatus is null
               or o.cleanupStatus <> com.coltrack.recordingservice.model.S3ObjectCleanupStatus.DELETED
            """)
    long sumActiveSizeBytes();

    @Query("""
            select count(o)
            from RecordingObjectEntity o
            where o.cleanupStatus is null
               or o.cleanupStatus <> com.coltrack.recordingservice.model.S3ObjectCleanupStatus.DELETED
            """)
    long countActiveObjects();

    @Query("""
            select count(distinct o.recordingId)
            from RecordingObjectEntity o
            where o.cleanupStatus is null
               or o.cleanupStatus <> com.coltrack.recordingservice.model.S3ObjectCleanupStatus.DELETED
            """)
    long countActiveRecordings();

    @Query("""
            select coalesce(sum(o.sizeBytes), 0)
            from RecordingObjectEntity o, RecordingEntity r
            where o.recordingId = r.id
              and r.protectedFromDeletion = true
              and (o.cleanupStatus is null or o.cleanupStatus <> com.coltrack.recordingservice.model.S3ObjectCleanupStatus.DELETED)
            """)
    long sumProtectedActiveSizeBytes();

}
