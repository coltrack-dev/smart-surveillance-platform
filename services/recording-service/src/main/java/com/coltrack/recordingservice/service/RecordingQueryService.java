package com.coltrack.recordingservice.service;

import com.coltrack.recordingservice.dto.RecordingDateProjection;
import com.coltrack.recordingservice.dto.RecordingDateResponse;
import com.coltrack.recordingservice.dto.RecordingResponse;
import com.coltrack.recordingservice.repository.RecordingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecordingQueryService {

    private final RecordingRepository recordingRepository;

    @Transactional(readOnly = true)
    public List<RecordingDateResponse> findAvailableDates(
            UUID cameraId
    ) {

        return recordingRepository
                .findAvailableDates(cameraId)
                .stream()
                .map(projection ->
                        new RecordingDateResponse(
                                projection.getRecordingDate(),
                                projection.getRecordingsCount()
                        )
                )
                .toList();
    }

    public List<RecordingResponse> findByDate(
            UUID cameraId,
            LocalDate date
    ) {

        Instant from =
                date.atStartOfDay(ZoneOffset.UTC)
                        .toInstant();

        Instant to =
                date.plusDays(1)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant();

        return recordingRepository
                .findByCameraIdAndStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtAsc(
                        cameraId,
                        from,
                        to
                )
                .stream()
                .map(RecordingResponse::from)
                .toList();
    }
}
