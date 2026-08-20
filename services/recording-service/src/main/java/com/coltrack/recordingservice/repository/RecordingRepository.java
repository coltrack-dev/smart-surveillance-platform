package com.coltrack.recordingservice.repository;

import com.coltrack.recordingservice.dto.RecordingDateProjection;
import com.coltrack.recordingservice.model.RecordingEntity;
import com.coltrack.recordingservice.model.RecordingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface RecordingRepository
        extends JpaRepository<RecordingEntity, UUID> {

    @Query(
            value = """
                    select
                        cast(started_at as date) as recording_date,
                        count(*) as recordings_count
                    from recording_sessions
                    where camera_id = :cameraId
                      and started_at is not null
                    group by cast(started_at as date)
                    order by recording_date desc
                    """,
            nativeQuery = true
    )
    List<RecordingDateProjection> findAvailableDates(
            @Param("cameraId") UUID cameraId
    );

    List<RecordingEntity>
    findByCameraIdAndStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtAsc(
            UUID cameraId,
            Instant from,
            Instant to
    );

    List<RecordingEntity> findByStatusIn(
            Collection<RecordingStatus> statuses
    );
}
